/*
 * ashell.c — native replacement for the shell-script `ashell` client.
 *
 * Motivace (2026-09-27, viz AGENTS.md #11 "Druhy zdroj audit boure"):
 * kazdy fork+exec (curl, python3, ...) generuje vlastni `avc: granted
 * { execute }` zaznam (MIUI vendor auditallow na untrusted_app_27/
 * app_data_file), a puvodni shell-skript `ashell` delal 2-3 externi execy
 * NA KAZDE volani (curl + python3 pro JSON/PTY bridge). Tenhle binarni
 * klient dela HTTP i binarni ashell_pty/shelldaemon protokoly primo
 * (raw sockety), takze bezne volani (`ashell -c ...`, `ashell adb ...`)
 * nedela ZADNY dalsi fork+exec — jen syscally uvnitr jednoho proces (ty
 * zadny "file execute" avc nespoustej).
 *
 * Bezi jako GUEST binarka (aarch64 glibc, stejny ABI jako rootfs) pod
 * PRootem — NENI to Android/NDK/bionic artefakt jako su_daemon/usb_bridge.
 * Sockety na 127.0.0.1 fungujou beznou cestou (PRoot nema network
 * namespace, jen ptrace syscall translation).
 *
 * Zachovava CLI grammar puvodniho /bin/sh skriptu (assets/ashell):
 *   ashell                          otevre host shell (cmd activity / HTTP)
 *   ashell --tmux|-tx               otevre host shell rovnou v tmuxu
 *   ashell -c|--cmd '<prikaz>'      spusti prikaz na hostiteli
 *   ashell adb start|stop|status    shell_daemon (uid 2000) lifecycle
 *   ashell adb shell [<cmd>]        interaktivni PTY / jednorazovy prikaz
 *   ashell adb <cmd>                = "adb shell <cmd>"
 *   ashell adb install|uninstall|push|pull|devices|help
 *   ashell --add|--remove|--list <cmd>   blocklist management
 *   ashell -e|--edit                edit ashell.conf v $EDITOR
 *
 * Nizko-frekventni interaktivni cesty (adb start/stop, -e, otevreni PTY
 * okna) klidne pouzivaji fork+exec (system()/execvp) — nejsou to hot
 * paths, ktere zaplavuji audit log. Hot paths (-c, adb <cmd>, adb -c)
 * jsou 100% raw syscall, bez externich procesu.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <termios.h>
#include <time.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>

#define API_PORT       1337
#define PTY_PORT       13340
#define API_HOST_TOKEN_PATH "/data/data/com.linux_core/shared_prefs/api_security.xml"

/* Verbose/debug marker: zapne se vlajkou `-v`/`--verbose` (před -c) nebo
 * env ASHELL_DEBUG=1. Když je zapnutý, `ashell -c ...` napíše na stderr,
 * kterou cestou příkaz reálně šel — přímý PTY most (127.0.0.1:13340) vs.
 * HTTP fallback (/shell). Slouží k ověření, že ashell_pty daemon žije. */
static int g_verbose = 0;

/* ssh-styl `-t`/`--tty`: vynutí serverový PTY pro `ashell -c` (job control,
 * isatty, resize). BEZ něj jede `-c` v levném pipe režimu (žádná kernelová
 * tty line-discipline, žádný per-loop traced ioctl pod prootem) — to je
 * default, protože `-c` je typicky jednorázový/skriptový příkaz. Interaktivní
 * TUI (htop, vi) chtějí `ashell -tc <cmd>`. */
static int g_force_pty = 0;

/* ── malé dynamické buffery ─────────────────────────────────────────── */

typedef struct {
    char *buf;
    size_t len;
    size_t cap;
} strbuf;

static void sb_init(strbuf *s) { s->buf = NULL; s->len = 0; s->cap = 0; }

static void sb_ensure(strbuf *s, size_t extra) {
    if (s->len + extra + 1 <= s->cap) return;
    size_t ncap = s->cap ? s->cap * 2 : 256;
    while (ncap < s->len + extra + 1) ncap *= 2;
    char *nb = realloc(s->buf, ncap);
    if (!nb) { fprintf(stderr, "[-] out of memory\n"); exit(1); }
    s->buf = nb;
    s->cap = ncap;
}

static void sb_append(strbuf *s, const char *data, size_t n) {
    sb_ensure(s, n);
    memcpy(s->buf + s->len, data, n);
    s->len += n;
    s->buf[s->len] = '\0';
}

static void sb_appends(strbuf *s, const char *str) { sb_append(s, str, strlen(str)); }

static void sb_free(strbuf *s) { free(s->buf); s->buf = NULL; s->len = s->cap = 0; }

/* ── low-level IO ────────────────────────────────────────────────────── */

static ssize_t write_all(int fd, const void *buf, size_t len) {
    size_t off = 0;
    const char *p = buf;
    while (off < len) {
        ssize_t n = write(fd, p + off, len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1;
        off += (size_t)n;
    }
    return (ssize_t)off;
}

static int tcp_connect(int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    addr.sin_addr.s_addr = htonl(0x7f000001u); /* 127.0.0.1 */
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* ── auth token z api_security.xml (bez externiho grep/sed) ─────────── */

static char *read_auth_token(void) {
    FILE *f = fopen(API_HOST_TOKEN_PATH, "r");
    if (!f) return NULL;
    char line[4096];
    char *result = NULL;
    while (fgets(line, sizeof(line), f)) {
        char *p = strstr(line, "name=\"auth_token\"");
        if (!p) continue;
        char *gt = strchr(p, '>');
        if (!gt) continue;
        gt++;
        char *lt = strchr(gt, '<');
        if (!lt) continue;
        size_t n = (size_t)(lt - gt);
        result = malloc(n + 1);
        if (result) { memcpy(result, gt, n); result[n] = '\0'; }
        break;
    }
    fclose(f);
    return result;
}

/* ── minimalní JSON extraktor (jen pro známé ploché tvary odpovědí) ──── */

static const char *json_find_key(const char *json, const char *key) {
    if (!json) return NULL;
    size_t klen = strlen(key);
    const char *p = json;
    while ((p = strchr(p, '"')) != NULL) {
        if (strncmp(p + 1, key, klen) == 0 && p[1 + klen] == '"') {
            const char *colon = p + 2 + klen;
            while (*colon == ' ' || *colon == '\t') colon++;
            if (*colon == ':') return colon + 1;
        }
        p++;
    }
    return NULL;
}

/* dekoduje JSON string escapes (\", \\, \/, \b, \f, \n, \r, \t, \uXXXX
 * jen BMP -> UTF-8); vrací malloc'd buffer nebo NULL. */
static char *json_get_string(const char *json, const char *key) {
    const char *v = json_find_key(json, key);
    if (!v) return NULL;
    while (*v == ' ' || *v == '\t') v++;
    if (*v != '"') {
        if (strncmp(v, "null", 4) == 0) return NULL;
        return NULL;
    }
    v++;
    strbuf out; sb_init(&out);
    while (*v && *v != '"') {
        if (*v == '\\' && v[1]) {
            v++;
            switch (*v) {
                case 'n': sb_append(&out, "\n", 1); break;
                case 'r': sb_append(&out, "\r", 1); break;
                case 't': sb_append(&out, "\t", 1); break;
                case 'b': sb_append(&out, "\b", 1); break;
                case 'f': sb_append(&out, "\f", 1); break;
                case '"': sb_append(&out, "\"", 1); break;
                case '\\': sb_append(&out, "\\", 1); break;
                case '/': sb_append(&out, "/", 1); break;
                case 'u': {
                    if (strlen(v) >= 5) {
                        unsigned int cp = 0;
                        sscanf(v + 1, "%4x", &cp);
                        if (cp < 0x80) {
                            char c = (char)cp; sb_append(&out, &c, 1);
                        } else if (cp < 0x800) {
                            char b[2] = { (char)(0xC0 | (cp >> 6)), (char)(0x80 | (cp & 0x3F)) };
                            sb_append(&out, b, 2);
                        } else {
                            char b[3] = { (char)(0xE0 | (cp >> 12)), (char)(0x80 | ((cp >> 6) & 0x3F)), (char)(0x80 | (cp & 0x3F)) };
                            sb_append(&out, b, 3);
                        }
                        v += 4;
                    }
                    break;
                }
                default: sb_append(&out, v, 1); break;
            }
            v++;
        } else {
            sb_append(&out, v, 1);
            v++;
        }
    }
    return out.buf ? out.buf : strdup("");
}

static long json_get_int(const char *json, const char *key, long def) {
    const char *v = json_find_key(json, key);
    if (!v) return def;
    while (*v == ' ' || *v == '\t') v++;
    if (*v == '"') { /* číslo posílané jako string */
        v++;
        return strtol(v, NULL, 10);
    }
    if (strncmp(v, "null", 4) == 0) return def;
    return strtol(v, NULL, 10);
}

static int json_get_bool(const char *json, const char *key, int def) {
    const char *v = json_find_key(json, key);
    if (!v) return def;
    while (*v == ' ' || *v == '\t') v++;
    if (strncmp(v, "true", 4) == 0) return 1;
    if (strncmp(v, "false", 5) == 0) return 0;
    return def;
}

static int json_has_key(const char *json, const char *key) {
    return json_find_key(json, key) != NULL;
}

/* JSON-escapuje string do strbuf (pro POST body, kdyz stavime {"cmd":"..."}) */
static void sb_append_json_escaped(strbuf *s, const char *str) {
    sb_append(s, "\"", 1);
    for (const unsigned char *p = (const unsigned char *)str; *p; p++) {
        switch (*p) {
            case '"': sb_appends(s, "\\\""); break;
            case '\\': sb_appends(s, "\\\\"); break;
            case '\n': sb_appends(s, "\\n"); break;
            case '\r': sb_appends(s, "\\r"); break;
            case '\t': sb_appends(s, "\\t"); break;
            default:
                if (*p < 0x20) {
                    char buf[8]; snprintf(buf, sizeof(buf), "\\u%04x", *p);
                    sb_appends(s, buf);
                } else {
                    sb_append(s, (const char *)p, 1);
                }
        }
    }
    sb_append(s, "\"", 1);
}

/* ── minimalní HTTP/1.1 klient přes 127.0.0.1:1337 ──────────────────── */

/* provede request, vrátí malloc'd tělo odpovědi (bez headers), nebo NULL
 * při chybě spojení. status_out (může být NULL) dostane HTTP status kód. */
static char *http_request(const char *method, const char *endpoint,
                           const char *body, const char *token,
                           const char *content_type, int *status_out) {
    int fd = tcp_connect(API_PORT);
    if (fd < 0) return NULL;

    size_t body_len = body ? strlen(body) : 0;
    strbuf req; sb_init(&req);
    sb_appends(&req, method);
    sb_append(&req, " ", 1);
    sb_appends(&req, endpoint);
    sb_appends(&req, " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n");
    if (token && *token) {
        sb_appends(&req, "Authorization: Bearer ");
        sb_appends(&req, token);
        sb_appends(&req, "\r\n");
    }
    if (body) {
        sb_appends(&req, "Content-Type: ");
        sb_appends(&req, content_type ? content_type : "text/plain");
        sb_appends(&req, "\r\nContent-Length: ");
        char lenbuf[32]; snprintf(lenbuf, sizeof(lenbuf), "%zu", body_len);
        sb_appends(&req, lenbuf);
        sb_appends(&req, "\r\n");
    }
    sb_appends(&req, "\r\n");
    if (body) sb_append(&req, body, body_len);

    if (write_all(fd, req.buf, req.len) < 0) {
        sb_free(&req);
        close(fd);
        return NULL;
    }
    sb_free(&req);

    strbuf resp; sb_init(&resp);
    char chunk[8192];
    ssize_t n;
    while ((n = read(fd, chunk, sizeof(chunk))) > 0) {
        sb_append(&resp, chunk, (size_t)n);
    }
    close(fd);

    if (resp.len == 0) { sb_free(&resp); return NULL; }

    /* rozdělit headers/body na "\r\n\r\n" */
    char *sep = strstr(resp.buf, "\r\n\r\n");
    int status = 0;
    if (resp.len >= 12) {
        sscanf(resp.buf, "HTTP/%*d.%*d %d", &status);
    }
    if (status_out) *status_out = status;
    if (!sep) { sb_free(&resp); return NULL; }
    char *out = strdup(sep + 4);
    sb_free(&resp);
    return out;
}

static void print_no_response(const char *endpoint) {
    fprintf(stderr, "[-] LocalApiServer neodpovídá na http://127.0.0.1:1337%s\n", endpoint);
}

/* ── vytiskne {stdout,stderr,exit_code} nebo {error[,exit_code]} tvar
 * odpovědi shelldaemon/shell endpointů a vrátí exit kód procesu ────── */
static int print_exec_response(const char *json) {
    if (!json) return 1;
    int has_exit = json_has_key(json, "exit_code");
    if (json_has_key(json, "error") && !has_exit) {
        char *out = json_get_string(json, "stdout");
        char *err = json_get_string(json, "stderr");
        char *errmsg = json_get_string(json, "error");
        if (out && *out) fputs(out, stdout);
        if (err && *err) fputs(err, stderr);
        if ((!out || !*out) && (!err || !*err) && errmsg) {
            fprintf(stderr, "%s\n", errmsg);
        }
        free(out); free(err); free(errmsg);
        return 1;
    }
    char *out = json_get_string(json, "stdout");
    char *err = json_get_string(json, "stderr");
    long rc = json_get_int(json, "exit_code", 0);
    if (out && *out) fputs(out, stdout);
    if (err && *err) fputs(err, stderr);
    free(out); free(err);
    if (rc < 0 || rc > 255) return rc != 0 ? 1 : 0;
    return (int)rc;
}

/* ── shelldaemon (uid 2000) přes /shelldaemon endpointy na appce ─────── */

static int shelldaemon_status(int *running, long *pid) {
    char *resp = http_request("GET", "/shelldaemon/status", NULL, NULL, NULL, NULL);
    if (!resp) return -1;
    int r = json_get_bool(resp, "running", 0);
    long p = json_get_int(resp, "pid", 0);
    free(resp);
    if (running) *running = r;
    if (pid) *pid = p;
    return 0;
}

static int shelldaemon_alive(void) {
    int running = 0;
    if (shelldaemon_status(&running, NULL) < 0) return 0;
    return running;
}

/* spustí <cmd> pod uid 2000 přes /shelldaemon/exec, vytiskne stdout/stderr,
 * vrátí exit kód. Jediné síto pro ashell adb <cmd>/shell <cmd>/-c <cmd>. */
static int daemon_exec(const char *cmd) {
    if (!shelldaemon_alive()) {
        fprintf(stderr, "[-] shell_daemon nebezi. Spust: ashell adb start\n");
        return 1;
    }
    char *resp = http_request("POST", "/shelldaemon/exec", cmd, NULL, "text/plain", NULL);
    if (!resp) { print_no_response("/shelldaemon/exec"); return 1; }
    int rc = print_exec_response(resp);
    free(resp);
    return rc;
}

/* ── ashell -c '<prikaz>' — primárně přes raw ashell_pty (13340) ────── */

static const unsigned char F_STDIN     = 0x01;
static const unsigned char F_STDOUT    = 0x02;
static const unsigned char F_WINCH     = 0x03;
static const unsigned char F_EXIT      = 0x04;
static const unsigned char F_HELLO     = 0x05;
static const unsigned char F_STDIN_EOF = 0x06;
static const unsigned char F_CLOSE     = 0xFF;

static int send_frame(int fd, unsigned char type, const void *payload, size_t len) {
    unsigned char hdr[5];
    hdr[0] = type;
    hdr[1] = (unsigned char)(len >> 24);
    hdr[2] = (unsigned char)(len >> 16);
    hdr[3] = (unsigned char)(len >> 8);
    hdr[4] = (unsigned char)(len);
    if (write_all(fd, hdr, 5) < 0) return -1;
    if (len > 0 && write_all(fd, payload, len) < 0) return -1;
    return 0;
}

/* PTY_UNAVAILABLE: ashell_pty neběží nebo handshake selhal -> HTTP fallback */
#define PTY_UNAVAILABLE (-1000)

/* SIGWINCH → jen nastaví flag; velikost okna se přepošle až v relay smyčce.
 * Díky tomu smyčka může blokovat na select() bez timeoutu a nemusí se každou
 * iteraci ptát TIOCGWINSZ (traced ioctl pod prootem). */
static volatile sig_atomic_t g_winch = 0;
static void on_sigwinch(int sig) { (void)sig; g_winch = 1; }

/* want_pty = žádost o serverový PTY (F_HELLO interactive flag). Když 0, běží
 * příkaz na hostu v pipe režimu (levnější, čistý výstup). Lokální terminál se
 * přepíná do raw módu jen v PTY režimu (jinak by to rozbilo cooked vstup pro
 * roury). */
static int run_via_ashell_pty(const char *cmd, const char *cwd, const char *rootfs,
                              const char *term, int want_pty) {
    int fd = tcp_connect(PTY_PORT);
    if (fd < 0) return PTY_UNAVAILABLE;

    int is_tty = isatty(STDIN_FILENO);
    int pty_mode = want_pty;              /* žádáme serverový PTY? */
    int raw_stdin = pty_mode && is_tty;   /* jen pak saháme na lokální terminál */

    struct termios old_termios;
    int have_old = 0;
    if (raw_stdin) {
        if (tcgetattr(STDIN_FILENO, &old_termios) == 0) {
            have_old = 1;
            struct termios raw = old_termios;
            cfmakeraw(&raw);
            tcsetattr(STDIN_FILENO, TCSANOW, &raw);
        }
    }

    /* SIGWINCH handler jen v PTY režimu s tty — bez SA_RESTART, aby select()
     * vracel EINTR a smyčka poslala novou velikost okna. */
    struct sigaction sa_old_winch;
    int winch_installed = 0;
    if (pty_mode && is_tty) {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = on_sigwinch;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = 0;
        if (sigaction(SIGWINCH, &sa, &sa_old_winch) == 0) {
            winch_installed = 1;
            g_winch = 1;                  /* vynuť první poslání velikosti */
        }
    }

    strbuf hello; sb_init(&hello);
    sb_appends(&hello, cmd ? cmd : "");
    sb_append(&hello, "\0", 1);
    sb_appends(&hello, cwd ? cwd : "");
    sb_append(&hello, "\0", 1);
    sb_appends(&hello, rootfs ? rootfs : "");
    sb_append(&hello, "\0", 1);
    sb_appends(&hello, term ? term : "xterm-256color");
    sb_append(&hello, "\0", 1);
    sb_appends(&hello, pty_mode ? "1" : "0");
    sb_append(&hello, "\0", 1);
    if (send_frame(fd, F_HELLO, hello.buf, hello.len) < 0) {
        sb_free(&hello);
        if (winch_installed) sigaction(SIGWINCH, &sa_old_winch, NULL);
        if (have_old) tcsetattr(STDIN_FILENO, TCSADRAIN, &old_termios);
        close(fd);
        return PTY_UNAVAILABLE;
    }
    sb_free(&hello);

    if (g_verbose)
        fprintf(stderr, "[ashell] via PTY (ashell_pty @ 127.0.0.1:%d, pty=%d)\n",
                PTY_PORT, pty_mode);

    int exit_code = 0;
    int stdin_open = 1;
    unsigned char buf[8192];

    for (;;) {
        /* Velikost okna posíláme jen když ji SIGWINCH označil (init + resize). */
        if (pty_mode && is_tty && g_winch) {
            g_winch = 0;
            struct winsize ws;
            if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) == 0 && ws.ws_col && ws.ws_row) {
                int cols = ws.ws_col, rows = ws.ws_row;
                unsigned char wp[8];
                wp[0] = (unsigned char)(cols >> 24); wp[1] = (unsigned char)(cols >> 16);
                wp[2] = (unsigned char)(cols >> 8);  wp[3] = (unsigned char)(cols);
                wp[4] = (unsigned char)(rows >> 24); wp[5] = (unsigned char)(rows >> 16);
                wp[6] = (unsigned char)(rows >> 8);  wp[7] = (unsigned char)(rows);
                send_frame(fd, F_WINCH, wp, 8);
            }
        }

        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(fd, &rfds);
        int maxfd = fd;
        if (stdin_open) { FD_SET(STDIN_FILENO, &rfds); if (STDIN_FILENO > maxfd) maxfd = STDIN_FILENO; }
        /* Blokující select (žádný timeout) → nula syscallů, když je klid.
         * SIGWINCH ho přeruší (EINTR) → nahoře přepošleme velikost. */
        int sel = select(maxfd + 1, &rfds, NULL, NULL, NULL);
        if (sel < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (FD_ISSET(fd, &rfds)) {
            unsigned char hdr[5];
            ssize_t got = read(fd, hdr, 5);
            if (got <= 0) break;
            ssize_t have = got;
            while (have < 5) {
                ssize_t r = read(fd, hdr + have, 5 - have);
                if (r <= 0) { have = -1; break; }
                have += r;
            }
            if (have < 0) break;
            unsigned char t = hdr[0];
            unsigned int plen = ((unsigned int)hdr[1] << 24) | ((unsigned int)hdr[2] << 16) |
                                 ((unsigned int)hdr[3] << 8) | hdr[4];
            unsigned char *payload = NULL;
            if (plen > 0) {
                payload = malloc(plen);
                size_t off = 0;
                int bad = 0;
                while (off < plen) {
                    ssize_t r = read(fd, payload + off, plen - off);
                    if (r <= 0) { bad = 1; break; }
                    off += (size_t)r;
                }
                if (bad) { free(payload); break; }
            }
            if (t == F_STDOUT) {
                if (payload) write_all(STDOUT_FILENO, payload, plen);
            } else if (t == F_EXIT) {
                if (plen >= 4) {
                    exit_code = ((unsigned int)payload[0] << 24 | (unsigned int)payload[1] << 16 |
                                 (unsigned int)payload[2] << 8 | payload[3]) & 0xFF;
                }
                free(payload);
                break;
            } else if (t == F_CLOSE) {
                free(payload);
                break;
            }
            free(payload);
        }

        if (stdin_open && FD_ISSET(STDIN_FILENO, &rfds)) {
            ssize_t r = read(STDIN_FILENO, buf, sizeof(buf));
            if (r <= 0) {
                send_frame(fd, F_STDIN_EOF, NULL, 0);
                stdin_open = 0;
            } else {
                send_frame(fd, F_STDIN, buf, (size_t)r);
            }
        }
    }

    if (winch_installed) sigaction(SIGWINCH, &sa_old_winch, NULL);
    if (have_old) tcsetattr(STDIN_FILENO, TCSADRAIN, &old_termios);
    close(fd);
    return exit_code;
}

/* HTTP fallback pro -c, kdyz ashell_pty (13340) neni k dispozici */
static int run_via_http_shell(const char *cmd, const char *token) {
    char *resp = http_request("POST", "/shell", cmd, token, "text/plain", NULL);
    if (!resp) { print_no_response("/shell"); return 1; }
    int rc = print_exec_response(resp);
    free(resp);
    return rc;
}

static int cmd_dash_c(int argc, char **argv) {
    if (argc < 1) {
        fprintf(stderr, "[-] Usage: ashell -c '<prikaz>'\n");
        fprintf(stderr, "    Pro multi-command: ashell -c \"ls -la && echo ok\"\n");
        fprintf(stderr, "    Interaktivni TUI (htop/vi): ashell -tc '<prikaz>' (vynuti PTY)\n");
        return 1;
    }
    strbuf joined; sb_init(&joined);
    for (int i = 0; i < argc; i++) {
        if (i) sb_append(&joined, " ", 1);
        sb_appends(&joined, argv[i]);
    }

    const char *rootfs_env = getenv("PROOT_L2S_DIR");
    char rootfs_host[4096] = "";
    if (rootfs_env && *rootfs_env) {
        strncpy(rootfs_host, rootfs_env, sizeof(rootfs_host) - 1);
        size_t l = strlen(rootfs_host);
        const char *suffix = "/.l2s";
        size_t sl = strlen(suffix);
        if (l >= sl && strcmp(rootfs_host + l - sl, suffix) == 0) {
            rootfs_host[l - sl] = '\0';
        }
    }
    char cwd[4096] = "";
    if (!getcwd(cwd, sizeof(cwd))) cwd[0] = '\0';
    const char *term = getenv("TERM");
    if (!term || !*term) term = "xterm-256color";

    int rc = run_via_ashell_pty(joined.buf, cwd, rootfs_host, term, g_force_pty);
    if (rc != PTY_UNAVAILABLE) {
        sb_free(&joined);
        return rc;
    }

    if (g_verbose)
        fprintf(stderr, "[ashell] via HTTP fallback (/shell @ 127.0.0.1:%d) — ashell_pty nedostupny\n",
                API_PORT);

    char *token = read_auth_token();
    rc = run_via_http_shell(joined.buf, token);
    free(token);
    sb_free(&joined);
    return rc;
}

/* ── ashell adb ... (shell_daemon lifecycle + passthrough) ──────────── */

/* spustí externí program a vrátí jeho exit kód (jen pro nízko-frekventní
 * interaktivní větve — start/stop přes real adb, otevření PTY okna). */
static int run_cmd_argv(char *const argv[]) {
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        execvp(argv[0], argv);
        _exit(127);
    }
    int status = 0;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return 1;
}

static void adb_help(void) {
    printf(
"ashell adb — adb-like rozhrani, vse pres shell_daemon (uid 2000)\n\n"
"  ashell adb start            spusti shell_daemon (jednorazove, pres adb shell)\n"
"  ashell adb stop             zastavi shell_daemon\n"
"  ashell adb status           stav shell_daemonu (TCP probe)\n"
"  ashell adb shell            otevre interaktivni terminal pod uid 2000\n"
"  ashell adb shell <cmd>      spusti <cmd> pod uid 2000\n"
"  ashell adb <cmd>            totez co \"adb shell <cmd>\" (prefix shell se zahodi)\n"
"  ashell adb install [-r] <apk>  legacy install: cp do /data/local/tmp + pm install\n"
"  ashell adb uninstall <pkg>     cmd package uninstall (jako adb)\n"
"  ashell adb push <L> <R>        cp -r L R (uid 2000)\n"
"  ashell adb pull <R> <L>        cp -r R L (uid 2000)\n"
"  ashell adb devices             nas shell_daemon jako jedine \"zarizeni\"\n"
"  ashell adb -c '<cmd>'          totez co \"ashell adb <cmd>\" (uid 2000, pres daemona)\n\n"
"Pozn.: daemon bezi jako Android shell (uid 2000), takze vidi /system/bin\n"
"nastroje (pm, am, logcat, settings, ...). `adb` binarka se nepouziva.\n");
}

static int cmd_adb(int argc, char **argv) {
    const char *sub = argc > 0 ? argv[0] : "status";

    if (strcmp(sub, "start") == 0) {
        if (system("command -v adb >/dev/null 2>&1") != 0) {
            fprintf(stderr, "[-] adb neni v guestu k dispozici.\n");
            return 1;
        }
        if (system("adb devices 2>/dev/null | grep -q device$") != 0) {
            fprintf(stderr, "[-] adb neni pripojeny. Nejdřív spáruj:\n");
            fprintf(stderr, "      adb pair <host>:<port> <pairing-code>\n");
            fprintf(stderr, "      adb connect <host>:<port>\n");
            return 1;
        }
        char *pathresp = http_request("GET", "/shelldaemon/info", NULL, NULL, NULL, NULL);
        char *daemon_path = pathresp ? json_get_string(pathresp, "path") : NULL;
        char *token = pathresp ? json_get_string(pathresp, "token") : NULL;
        free(pathresp);
        const char *token_override = getenv("SHELLDAEMON_TOKEN");
        if (token_override && *token_override) {
            free(token);
            token = strdup(token_override);
        }
        if (!daemon_path || !*daemon_path) {
            fprintf(stderr, "[-] Neznám cestu k libshelldaemon.so.\n");
            fprintf(stderr, "    Ověř, že appka běží: curl -s http://127.0.0.1:1337/shelldaemon/info\n");
            free(daemon_path); free(token);
            return 1;
        }
        if (!token || !*token) {
            fprintf(stderr, "[-] Nepodařilo se získat token daemona.\n");
            free(daemon_path); free(token);
            return 1;
        }
        if (!shelldaemon_alive()) {
            printf("[*] Startuji libshelldaemon.so pod shell UID (non-root) ...\n");
            char launch[8192];
            snprintf(launch, sizeof(launch),
                     "adb shell \"nohup %s --port=13341 --token=%s >/dev/null 2>&1 &\" >/dev/null 2>&1",
                     daemon_path, token);
            system(launch);
            struct timespec ts = { 2, 0 };
            nanosleep(&ts, NULL);
        }
        long pid = 0; int running = 0;
        shelldaemon_status(&running, &pid);
        if (running) {
            char uidcmd[512], uidout[256] = "";
            snprintf(uidcmd, sizeof(uidcmd),
                     "adb shell \"cat /proc/%ld/status 2>/dev/null | grep '^Uid:' | awk '{print \\$2}'\"",
                     pid);
            FILE *pf = popen(uidcmd, "r");
            if (pf) { if (fgets(uidout, sizeof(uidout), pf)) { size_t l = strlen(uidout); while (l && (uidout[l-1]=='\n'||uidout[l-1]=='\r')) uidout[--l]='\0'; } pclose(pf); }
            printf("[+] shell_daemon běží (pid=%ld, uid=%s, non-root)\n", pid, uidout[0] ? uidout : "?");
            printf("    Příkazy: ashell adb <cmd>   (napr. ashell adb id, ashell adb pm list packages)\n");
            free(daemon_path); free(token);
            return 0;
        }
        fprintf(stderr, "[-] shell_daemon nenabehl.\n");
        free(daemon_path); free(token);
        return 1;
    }

    if (strcmp(sub, "stop") == 0) {
        char *resp = http_request("POST", "/shelldaemon/stop", NULL, NULL, NULL, NULL);
        int stopped_via_api = resp && !shelldaemon_alive();
        free(resp);
        if (!stopped_via_api) {
            if (system("command -v adb >/dev/null 2>&1 && adb devices 2>/dev/null | grep -q device$") == 0) {
                system("adb shell \"cat /data/local/tmp/shelldaemon.pid 2>/dev/null\" > /tmp/.ashell_pid_$$ 2>/dev/null");
                system("_p=$(cat /tmp/.ashell_pid_$$ 2>/dev/null); [ -n \"$_p\" ] && adb shell \"kill $_p 2>/dev/null; sleep 1; kill -9 $_p 2>/dev/null || true\" >/dev/null 2>&1; rm -f /tmp/.ashell_pid_$$");
                system("adb shell \"pkill -f libshelldaemon 2>/dev/null || true\" >/dev/null 2>&1");
                system("adb shell \"rm -f /data/local/tmp/shelldaemon.pid 2>/dev/null || true\" >/dev/null 2>&1");
                struct timespec ts = { 1, 0 };
                nanosleep(&ts, NULL);
            }
        }
        if (shelldaemon_alive()) {
            fprintf(stderr, "[!] shell_daemon stale bezi (stop se nezdaril).\n");
            return 1;
        }
        printf("[+] shell_daemon stopped\n");
        return 0;
    }

    if (strcmp(sub, "status") == 0) {
        int running = 0; long pid = 0;
        if (shelldaemon_status(&running, &pid) < 0) {
            fprintf(stderr, "[-] LocalApiServer neodpovídá na http://127.0.0.1:1337/shelldaemon/status\n");
            return 1;
        }
        if (running) printf("[+] shell_daemon běží (pid=%ld, port=13341)\n", pid);
        else printf("[-] shell_daemon neběží\n");
        return 0;
    }

    if (strcmp(sub, "help") == 0 || strcmp(sub, "--help") == 0 || strcmp(sub, "-h") == 0) {
        adb_help();
        return 0;
    }

    if (strcmp(sub, "devices") == 0) {
        int running = 0; long pid = 0;
        shelldaemon_status(&running, &pid);
        printf("List of devices attached\n");
        if (running) printf("127.0.0.1:13341\tdevice (shell_daemon uid 2000, pid %ld)\n", pid);
        return 0;
    }

    if (strcmp(sub, "shell") == 0) {
        if (argc > 1) {
            strbuf joined; sb_init(&joined);
            for (int i = 1; i < argc; i++) { if (i > 1) sb_append(&joined, " ", 1); sb_appends(&joined, argv[i]); }
            int rc = daemon_exec(joined.buf);
            sb_free(&joined);
            return rc;
        }
        printf("[*] Otevírám nové terminálové okno pod uid 2000 (shell_daemon)...\n");
        char *cmdargv[] = {
            "cmd", "activity", "start-activity", "-n",
            "com.linux_core/com.linux_core.ui.terminal.TerminalActivity",
            "--es", "rootfsDirName", "ashell-adb",
            "--ez", "mountStorage", "false", NULL
        };
        if (run_cmd_argv(cmdargv) == 0) {
            printf("[+] ADB shell opened (via cmd activity)\n");
            return 0;
        }
        char *resp = http_request("POST", "/ashell", "{\"mode\":\"adb-shell\"}", NULL, "application/json", NULL);
        if (!resp) { print_no_response("/ashell"); return 1; }
        printf("[+] ADB shell opened (via API)\n");
        free(resp);
        return 0;
    }

    if (strcmp(sub, "install") == 0) {
        if (argc < 2) { fprintf(stderr, "[-] Usage: ashell adb install [-r] [-g] <apk>\n"); return 1; }
        const char *apk = NULL;
        strbuf opts; sb_init(&opts);
        for (int i = 1; i < argc; i++) {
            if (argv[i][0] == '-') { sb_appends(&opts, " "); sb_appends(&opts, argv[i]); }
            else apk = argv[i];
        }
        if (!apk) { fprintf(stderr, "[-] Usage: ashell adb install [-r] [-g] <apk>\n"); sb_free(&opts); return 1; }
        strbuf body; sb_init(&body);
        sb_appends(&body, "{\"apk\":"); sb_append_json_escaped(&body, apk);
        sb_appends(&body, ",\"args\":"); sb_append_json_escaped(&body, opts.buf ? opts.buf : "");
        sb_appends(&body, "}");
        char *resp = http_request("POST", "/shelldaemon/install", body.buf, NULL, "application/json", NULL);
        sb_free(&body);
        if (resp && !json_has_key(resp, "error")) {
            int rc = print_exec_response(resp);
            free(resp); sb_free(&opts);
            return rc;
        }
        free(resp);
        const char *base = strrchr(apk, '/');
        base = base ? base + 1 : apk;
        strbuf cmd; sb_init(&cmd);
        sb_appends(&cmd, "cp '"); sb_appends(&cmd, apk); sb_appends(&cmd, "' '/data/local/tmp/"); sb_appends(&cmd, base);
        sb_appends(&cmd, "' && pm install"); sb_appends(&cmd, opts.buf ? opts.buf : "");
        sb_appends(&cmd, " '/data/local/tmp/"); sb_appends(&cmd, base); sb_appends(&cmd, "'");
        int rc = daemon_exec(cmd.buf);
        sb_free(&cmd); sb_free(&opts);
        return rc;
    }

    if (strcmp(sub, "uninstall") == 0) {
        strbuf cmd; sb_init(&cmd);
        sb_appends(&cmd, "cmd package uninstall");
        for (int i = 1; i < argc; i++) { sb_append(&cmd, " ", 1); sb_appends(&cmd, argv[i]); }
        sb_appends(&cmd, " 2>/dev/null || pm uninstall");
        for (int i = 1; i < argc; i++) { sb_append(&cmd, " ", 1); sb_appends(&cmd, argv[i]); }
        int rc = daemon_exec(cmd.buf);
        sb_free(&cmd);
        return rc;
    }

    if (strcmp(sub, "push") == 0 || strcmp(sub, "pull") == 0) {
        if (argc < 3) { fprintf(stderr, "[-] Usage: ashell adb %s <src> <dst>\n", sub); return 1; }
        strbuf cmd; sb_init(&cmd);
        sb_appends(&cmd, "cp -r '"); sb_appends(&cmd, argv[1]); sb_appends(&cmd, "' '");
        for (int i = 2; i < argc; i++) { if (i > 2) sb_append(&cmd, " ", 1); sb_appends(&cmd, argv[i]); }
        sb_appends(&cmd, "'");
        int rc = daemon_exec(cmd.buf);
        sb_free(&cmd);
        return rc;
    }

    if (strcmp(sub, "-c") == 0 || strcmp(sub, "--cmd") == 0) {
        if (argc < 2) {
            fprintf(stderr, "[-] Usage: ashell adb -c '<prikaz>' (app UID)\n");
            fprintf(stderr, "    Pro uid 2000: ashell adb <prikaz>\n");
            return 1;
        }
        strbuf joined; sb_init(&joined);
        for (int i = 1; i < argc; i++) { if (i > 1) sb_append(&joined, " ", 1); sb_appends(&joined, argv[i]); }
        char *resp = http_request("POST", "/shelldaemon/exec", joined.buf, NULL, "text/plain", NULL);
        sb_free(&joined);
        if (!resp) { print_no_response("/shelldaemon/exec"); return 1; }
        int rc = print_exec_response(resp);
        free(resp);
        return rc;
    }

    /* default: "ashell adb <cokoli>" = "adb shell <cokoli>" přes daemon_exec */
    strbuf joined; sb_init(&joined);
    for (int i = 0; i < argc; i++) { if (i) sb_append(&joined, " ", 1); sb_appends(&joined, argv[i]); }
    int rc = daemon_exec(joined.buf);
    sb_free(&joined);
    return rc;
}

/* ── --add/--remove/--list/-e/--edit (blocklist + config) ────────────── */

static int cmd_blocklist_add_remove(const char *action, const char *cmd, char *token) {
    strbuf body; sb_init(&body);
    sb_appends(&body, "{\"cmd\":"); sb_append_json_escaped(&body, cmd);
    sb_appends(&body, ",\"action\":\""); sb_appends(&body, action); sb_appends(&body, "\"}");
    char *resp = http_request("POST", "/ashell/blocklist", body.buf, token, "application/json", NULL);
    sb_free(&body);
    if (!resp) { print_no_response("/ashell/blocklist"); return 1; }
    char *status = json_get_string(resp, "status");
    long blocked_count = json_get_int(resp, "blocked_count", 0);
    printf("[+] %s %s (blocked_count=%ld)\n", status ? status : "?", cmd, blocked_count);
    free(status);
    free(resp);
    return 0;
}

static int cmd_blocklist_list(char *token) {
    char *resp = http_request("GET", "/ashell/blocklist", NULL, token, NULL, NULL);
    if (!resp) { print_no_response("/ashell/blocklist"); return 1; }
    char *source = json_get_string(resp, "source");
    /* velmi jednoduché vypsání "commands" array — pole stringů */
    const char *arr = json_find_key(resp, "commands");
    int count = 0;
    strbuf lines; sb_init(&lines);
    if (arr) {
        while (*arr == ' ') arr++;
        if (*arr == '[') {
            const char *p = arr + 1;
            while (*p && *p != ']') {
                while (*p == ' ' || *p == ',') p++;
                if (*p == '"') {
                    p++;
                    strbuf item; sb_init(&item);
                    while (*p && *p != '"') {
                        if (*p == '\\' && p[1]) { p++; sb_append(&item, p, 1); p++; }
                        else { sb_append(&item, p, 1); p++; }
                    }
                    if (*p == '"') p++;
                    sb_appends(&lines, "  - "); sb_appends(&lines, item.buf ? item.buf : ""); sb_appends(&lines, "\n");
                    sb_free(&item);
                    count++;
                } else if (*p != ']') {
                    p++;
                }
            }
        }
    }
    printf("Blocklist (%d) — zdroj: %s\n", count, source ? source : "ashell.conf");
    if (count) fputs(lines.buf, stdout);
    else printf("  (empty — všechny příkazy povoleny)\n");
    sb_free(&lines);
    free(source);
    free(resp);
    return 0;
}

static int cmd_config_edit(char *token) {
    const char *editor = getenv("EDITOR");
    if (!editor || !*editor) {
        fprintf(stderr, "[-] Proměnná EDITOR není nastavena.\n");
        fprintf(stderr, "    Nastav ji např.:  export EDITOR=nano    (nebo vi/vim)\n");
        fprintf(stderr, "    Pak znovu spusť:  ashell -e\n");
        return 1;
    }
    char *resp = http_request("GET", "/ashell/config", NULL, token, NULL, NULL);
    if (!resp || !*resp) {
        fprintf(stderr, "[-] Nelze stáhnout konfiguraci z http://127.0.0.1:1337/ashell/config\n");
        free(resp);
        return 1;
    }
    char tmpfile[64];
    snprintf(tmpfile, sizeof(tmpfile), "/tmp/.ashell_conf_%d.conf", getpid());
    FILE *f = fopen(tmpfile, "w");
    if (!f) { free(resp); return 1; }
    fwrite(resp, 1, strlen(resp), f);
    fclose(f);
    free(resp);

    fprintf(stderr, "[*] Otevírám konfiguraci v %s (ulož + ukonči pro aplikaci)...\n", editor);
    fprintf(stderr, "    Řádky 'block <cmd>' = blokace, ostatní řádky = env před každým příkazem.\n");
    char *editargv[] = { (char *)editor, tmpfile, NULL };
    int rc = run_cmd_argv(editargv);
    if (rc != 0) {
        fprintf(stderr, "[-] Editor skončil s chybou (RC=%d) — změny NEbyly uloženy.\n", rc);
        remove(tmpfile);
        return rc;
    }

    f = fopen(tmpfile, "r");
    if (!f) { remove(tmpfile); return 1; }
    strbuf content; sb_init(&content);
    char chunk[4096]; size_t n;
    while ((n = fread(chunk, 1, sizeof(chunk), f)) > 0) sb_append(&content, chunk, n);
    fclose(f);
    remove(tmpfile);

    char *result = http_request("POST", "/ashell/config", content.buf, token, "text/plain", NULL);
    sb_free(&content);
    if (result && strstr(result, "\"config_saved\"")) {
        printf("[+] Konfigurace uložena — aplikuje se od dalšího ashell -c / nového host shellu.\n");
        free(result);
        return 0;
    }
    fprintf(stderr, "[-] Uložení selhalo: %s\n", result ? result : "(no response)");
    free(result);
    return 1;
}

/* ── bare `ashell` — otevři host shell (mimo PRoot) ──────────────────── */

static int cmd_open_host_shell(int use_tmux) {
    printf("[*] Opouštím PRoot container → host app shell%s...\n",
           use_tmux ? " (tmux)" : "");
    /* --tmux/-tx: predame TerminalActivity extra ashellTmux=true, aby session
     * bezela rovnou v tmuxu misto holeho sh (viz startAshellSession). */
    char *cmdargv_plain[] = {
        "cmd", "activity", "start-activity", "-n",
        "com.linux_core/com.linux_core.ui.terminal.TerminalActivity",
        "--es", "rootfsDirName", "ashell-host",
        "--ez", "mountStorage", "false",
        "--ez", "ashellMode", "true", NULL
    };
    char *cmdargv_tmux[] = {
        "cmd", "activity", "start-activity", "-n",
        "com.linux_core/com.linux_core.ui.terminal.TerminalActivity",
        "--es", "rootfsDirName", "ashell-host",
        "--ez", "mountStorage", "false",
        "--ez", "ashellMode", "true",
        "--ez", "ashellTmux", "true", NULL
    };
    if (run_cmd_argv(use_tmux ? cmdargv_tmux : cmdargv_plain) == 0) {
        printf("[+] Host shell started (via cmd activity)%s\n",
               use_tmux ? " — tmux session 'ashell'" : "");
        printf("    Nový terminál otevřen — jste v Android host shellu.\n");
        return 0;
    }
    fprintf(stderr, "[!] cmd activity se nezdařil, zkouším fallback...\n");

    char *token = read_auth_token();
    char *resp = http_request("POST", "/ashell",
                              use_tmux ? "{\"tmux\":true}" : NULL,
                              token, use_tmux ? "application/json" : NULL, NULL);
    free(token);
    if (!resp) {
        print_no_response("/ashell");
        fprintf(stderr, "    Zkus ručně: cmd activity start-activity -n com.linux_core/com.linux_core.ui.terminal.TerminalActivity --es rootfsDirName ashell-host --ez ashellMode true\n");
        return 1;
    }
    char *status = json_get_string(resp, "status");
    char *user = json_get_string(resp, "user");
    long uid = json_get_int(resp, "uid", -1);
    char *pwd = json_get_string(resp, "pwd");
    printf("[+] %s (uid=%ld, user=%s)\n", status ? status : "done", uid, user ? user : "?");
    printf("    Host filesDir: %s\n\n", pwd ? pwd : "?");
    printf("    Nový terminál otevřen — jste v Android host shellu.\n");
    free(status); free(user); free(pwd); free(resp);
    return 0;
}

/* ── main ─────────────────────────────────────────────────────────────── */

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);

    const char *dbg_env = getenv("ASHELL_DEBUG");
    if (dbg_env && *dbg_env && strcmp(dbg_env, "0") != 0) g_verbose = 1;

    /* Vedoucí boolean vlajky (před podpříkazem): `-v`/`--verbose` (debug marker),
     * `-t`/`--tty` (vynuť serverový PTY). Podporováno oddělené (`-v -t -c`) i
     * slepené getopt clustery (`-vtc`, `-tc`, `-vc`, `-ve`). Terminální selektory
     * `-c`/`-e` berou argument, takže ve slepeném clusteru musí být POSLEDNÍ;
     * opačné pořadí (`-cv`) by bylo dvojznačné a NEpodporuje se. */
    while (argc >= 2) {
        if (strcmp(argv[1], "-v") == 0 || strcmp(argv[1], "--verbose") == 0) {
            g_verbose = 1;
        } else if (strcmp(argv[1], "-t") == 0 || strcmp(argv[1], "--tty") == 0) {
            g_force_pty = 1;
        } else {
            break;
        }
        argv[1] = argv[0]; argv++; argc--;   /* spotřebuj vlajku, posuň argv */
    }
    /* Slepený short cluster booleanů (+ volitelný terminální selektor na konci). */
    if (argc >= 2 && argv[1][0] == '-' && argv[1][1] != '-' && argv[1][1] != '\0') {
        const char *cl = argv[1] + 1;   /* přeskoč '-' */
        int all_known = 1;
        for (const char *p = cl; *p; p++) {
            if (*p == 'v' || *p == 't') continue;
            if ((*p == 'c' || *p == 'e') && p[1] == '\0') continue;   /* selektor, jen poslední */
            all_known = 0; break;
        }
        if (all_known) {
            char sel = '\0';
            for (const char *p = cl; *p; p++) {
                if (*p == 'v') g_verbose = 1;
                else if (*p == 't') g_force_pty = 1;
                else sel = *p;   /* 'c' nebo 'e' */
            }
            if (sel) {
                static char remapped[3] = { '-', '\0', '\0' };
                remapped[1] = sel;
                argv[1] = remapped;
            } else {
                argv[1] = argv[0]; argv++; argc--;   /* cluster jen booleanů (`-vt`) */
            }
        }
    }

    if (argc >= 2 && strcmp(argv[1], "adb") == 0) {
        return cmd_adb(argc - 2, argv + 2);
    }
    if (argc >= 2 && (strcmp(argv[1], "-c") == 0 || strcmp(argv[1], "--cmd") == 0)) {
        return cmd_dash_c(argc - 2, argv + 2);
    }
    if (argc >= 2 && (strcmp(argv[1], "--add") == 0 || strcmp(argv[1], "--remove") == 0)) {
        if (argc < 3) {
            fprintf(stderr, "[-] Usage: ashell %s <cmd>\n", argv[1]);
            return 1;
        }
        char *token = read_auth_token();
        int rc = cmd_blocklist_add_remove(strcmp(argv[1], "--add") == 0 ? "add" : "remove", argv[2], token);
        free(token);
        return rc;
    }
    if (argc >= 2 && strcmp(argv[1], "--list") == 0) {
        char *token = read_auth_token();
        int rc = cmd_blocklist_list(token);
        free(token);
        return rc;
    }
    if (argc >= 2 && (strcmp(argv[1], "-e") == 0 || strcmp(argv[1], "--edit") == 0)) {
        char *token = read_auth_token();
        int rc = cmd_config_edit(token);
        free(token);
        return rc;
    }

    /* bare `ashell [--tmux|-tx]` → host shell (mimo proot, uid appky).
     * Flag muze byt kdekoli v argv (zbytek argumentu se pro holy host shell
     * neinterpretuje). Zadny jiny subcommand sem nedosahne — vsechny vyse
     * jsou exact-match na argv[1] a vraceji driv. */
    int use_tmux = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--tmux") == 0 || strcmp(argv[i], "-tx") == 0) use_tmux = 1;
    }
    return cmd_open_host_shell(use_tmux);
}
