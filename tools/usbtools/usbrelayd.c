/*
 * usbrelayd — TCP control daemon for USB HID relay boards.
 *
 * Bionic/ARM64 host-side daemon (PRoot guest + Android host).
 * Built against darrylb123/usbrelay (libusbrelay.c) + hidapi (libusb
 * backend). Replaces the upstream Python/MQTT "usbrelayd" with a compiled
 * C daemon so it fits the bionic assets/usr toolchain (no CPython ABI).
 *
 * Protocol: one text command per line over TCP, LF-terminated.
 *   PING                          -> PONG
 *   LIST                          -> <serial> <relay_count> <state_hex>
 *   DETAILS                       -> serial,count,state,path,module_type
 *   STATE <serial>                -> <state_hex>          (bit n-1 = relay n)
 *   <serial>_<n>=<0|1>            -> set relay n off/on  (CMD_OFF=0xfd/CMD_ON=0xff)
 *   <serial>_0=<newserial>        -> rename board serial
 * Answers:  OK <detail>  |  ERR <reason>
 *
 * Usage: usbrelayd [-d] [-v] [-p PORT] [-H HOST]
 *   -d  stay in foreground with debug output (no daemonize)
 *   -p  TCP port      (default 8787)
 *   -H  bind address  (default 127.0.0.1)
 *
 * Enumerates relay boards ONCE at startup (libusbrelay keeps a static
 * board list; shutdown() does not reset it, so no runtime rescan).
 * License: GPL-2.0-or-later, aligned with the usbrelay project.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <ctype.h>
#include <fcntl.h>
#include <signal.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/in.h>

/* libusbrelay.h deklaruje int shutdown(struct usb_relay_device *) — koliduje
 * s Bionic shutdown(int,int) z <sys/socket.h> (conflicting types). Funkci
 * shutdown() zde nevoláme, takže deklaraci stačí v tomto TU přejmenovat. */
#define shutdown usbrelay_shutdown
#include "libusbrelay.h"
#undef shutdown

#ifndef NULL
#define NULL ((void*)0)
#endif

#define DEFAULT_PORT 8787
#define CMD_ON  0xff
#define CMD_OFF 0xfd

static int g_debug = 0;

/* ── helpers (trivial, no external deps) ─────────────────────────────── */
static void trim(char *s)
{
    size_t n = strlen(s);
    while (n > 0 && (s[n-1] == '\n' || s[n-1] == '\r' || s[n-1] == ' '))
        s[--n] = '\0';
}

/* per-board line for LIST */
static void board_line(char *out, size_t outsz, const relay_board *b)
{
    snprintf(out, outsz, "%s %u %02x", b->serial, b->relay_count, b->state);
}

/* handles one command line; writes reply into buf */
static void handle_cmd(const char *cmd, char *buf, size_t bufsz)
{
    buf[0] = '\0';
    if (strcmp(cmd, "PING") == 0) {
        snprintf(buf, bufsz, "PONG");
        return;
    }
    if (strcmp(cmd, "LIST") == 0) {
        int i, n = get_relay_board_count();
        if (n == 0) {
            snprintf(buf, bufsz, "OK 0 boards");
            return;
        }
        relay_board *bs = get_relay_boards();
        size_t off = 0;
        for (i = 0; i < n && off < bufsz; i++) {
            char line[128];
            board_line(line, sizeof(line), &bs[i]);
            off += snprintf(buf + off, bufsz - off, "%s%s", i ? " " : "", line);
        }
        return;
    }
    if (strcmp(cmd, "DETAILS") == 0) {
        int i, n = get_relay_board_count();
        relay_board *bs = get_relay_boards();
        size_t off = 0;
        for (i = 0; i < n && off < bufsz; i++) {
            off += snprintf(buf + off, bufsz - off,
                            "%sserial=%s,relays=%u,state=%02x,path=%s,type=%d",
                            i ? " " : "", bs[i].serial, bs[i].relay_count,
                            bs[i].state, bs[i].path ? bs[i].path : "?", bs[i].module_type);
        }
        if (n == 0)
            snprintf(buf, bufsz, "OK 0 boards");
        return;
    }
    if (strncmp(cmd, "STATE ", 6) == 0) {
        relay_board *b = find_board(cmd + 6, g_debug);
        if (!b) { snprintf(buf, bufsz, "ERR board not found: %s", cmd + 6); return; }
        snprintf(buf, bufsz, "OK %02x", b->state);
        return;
    }
    /* <serial>_<n>=<0|1>  or  <serial>_0=<newserial> */
    {
        const char *eq = strchr(cmd, '=');
        const char *us = strrchr(cmd, '_');
        if (eq && us && us < eq) {
            /* serial = [0, us), relay_num = atoi(us+1) */
            size_t slen = (size_t)(us - cmd);
            char serial[32];
            if (slen >= sizeof(serial)) { snprintf(buf, bufsz, "ERR serial too long"); return; }
            memcpy(serial, cmd, slen); serial[slen] = '\0';
            int relay = atoi(us + 1);
            if (relay == 0) {
                /* rename serial */
                const char *ns = eq + 1;
                if (!*ns) { snprintf(buf, bufsz, "ERR empty new serial"); return; }
                if (set_serial(serial, (char*)ns, g_debug) < 0)
                    snprintf(buf, bufsz, "ERR set_serial failed");
                else
                    snprintf(buf, bufsz, "OK serial now %s", ns);
                return;
            }
            int on = atoi(eq + 1);
            unsigned char target = on ? CMD_ON : CMD_OFF;
            if (operate_relay(serial, (unsigned char)relay, target, g_debug) < 0) {
                snprintf(buf, bufsz, "ERR operate %s_%d=%d failed (unknown board? no permission?)",
                         serial, relay, on);
                return;
            }
            relay_board *b = find_board(serial, g_debug);
            snprintf(buf, bufsz, "OK %s_%d=%d state=%02x",
                     serial, relay, on, b ? b->state : 0);
            return;
        }
    }
    snprintf(buf, bufsz, "ERR unknown command (try LIST/DETAILS/STATE/PING or SERIAL_n=0|1)");
}

int main(int argc, char **argv)
{
    int port = DEFAULT_PORT;
    const char *host = "127.0.0.1";
    int foreground = 0, verbose = 0;
    int opt;

    while ((opt = getopt(argc, argv, "dp:H:vh")) != -1) {
        switch (opt) {
        case 'd': foreground = 1; g_debug = 1; break;
        case 'p': port = atoi(optarg); break;
        case 'H': host = optarg; break;
        case 'v': verbose = 1; break;
        case 'h':
        default:
            fprintf(stderr,
                    "usage: %s [-d] [-v] [-p PORT] [-H HOST]\n"
                    "  TCP daemon for USB HID relay boards (libusbrelay).\n"
                    "  commands: PING | LIST | DETAILS | STATE <serial> |\n"
                    "            <serial>_<n>=<0|1> | <serial>_0=<newserial>\n",
                    argv[0]);
            return opt == 'h' ? 0 : 1;
        }
    }

    /* enumerate once — libusbrelay holds a static list */
    if (enumerate_relay_boards(NULL, verbose, g_debug) < 0) {
        fprintf(stderr, "usbrelayd: no relay boards found (run as root; check /dev/bus/usb)\n");
        /* keep going: LIST will report 0 boards */
    }
    int n = get_relay_board_count();
    if (verbose)
        fprintf(stderr, "usbrelayd: %d board(s) detected\n", n);

    int srv = socket(AF_INET, SOCK_STREAM, 0);
    if (srv < 0) { perror("socket"); return 1; }
    int one = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short)port);
    if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
        fprintf(stderr, "usbrelayd: bad HOST %s\n", host);
        return 1;
    }
    if (bind(srv, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("bind"); return 1;
    }
    if (listen(srv, 5) < 0) { perror("listen"); return 1; }

    if (!foreground) {
        /* daemonize */
        pid_t pid = fork();
        if (pid < 0) { perror("fork"); return 1; }
        if (pid > 0) return 0;               /* parent exits */
        setsid();
        /* keep stdout/stderr for debug, redirect stdin */
        freopen("/dev/null", "r", stdin);
    }
    /* ignore broken pipe on client disconnect */
    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_IGN);

    fprintf(stderr, "usbrelayd: listening on %s:%d (%d board(s))\n",
            host, port, n);

    for (;;) {
        int cli = accept(srv, NULL, NULL);
        if (cli < 0) { if (errno == EINTR) continue; break; }

        char buf[512];
        ssize_t got;
        long line_pos = 0;
        /* read line-by-line */
        while ((got = read(cli, buf + line_pos, sizeof(buf) - 1 - line_pos)) > 0) {
            line_pos += got;
            buf[line_pos] = '\0';
            char *nl;
            while ((nl = strchr(buf, '\n')) != NULL) {
                *nl = '\0';
                char *line = (line_pos > 0 && buf[0] == '\0') ? NULL : buf;
                if (line && *line) {
                    trim(line);
                    char reply[1024];
                    handle_cmd(line, reply, sizeof(reply));
                    size_t rl = strlen(reply);
                    size_t off = 0;
                    while (off < rl) {
                        ssize_t w = write(cli, reply + off, rl - off);
                        if (w <= 0) break;
                        off += (size_t)w;
                    }
                    write(cli, "\n", 1);
                }
                /* shift remaining */
                char *rest = nl + 1;
                size_t rest_len = (size_t)(buf + line_pos - rest);
                if (rest_len > 0) memmove(buf, rest, rest_len);
                line_pos = (long)rest_len;
                buf[line_pos] = '\0';
            }
            if (line_pos >= (long)sizeof(buf) - 2) {  /* pathological line */
                line_pos = 0; buf[0] = '\0';
            }
        }
        close(cli);
    }
    return 0;
}