/*
 * usb_bridge.c — PRoot-side USB ioctl bridge (standalone ARM64 binary)
 *
 * Deployed into PRoot at /usr/local/bin/usb_bridge.
 * Connects to the Android app's UDS, receives a USB device fd via SCM_RIGHTS,
 * then performs direct ioctl(2) operations for USB bulk/control transfers.
 *
 * Build for ARM64 (inside PRoot, links against Android NDK Bionic):
 *   aarch64-linux-android24-clang -static -o usb_bridge usb_bridge.c
 *
 * Usage inside PRoot:
 *   usb_bridge <uds_path> bulk <ep> <in|out> <len> [timeout_ms]
 *   usb_bridge <uds_path> claim <interface>
 *   usb_bridge <uds_path> release <interface>
 *   usb_bridge <uds_path> reset
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/ioctl.h>
#include <linux/types.h>
#include <linux/usbdevice_fs.h>

#define CHECK(cond, msg) do { \
    if (!(cond)) { fprintf(stderr, "ERROR: %s (errno=%d: %s)\n", msg, errno, strerror(errno)); exit(1); } \
} while(0)

static int usb_fd = -1;

// ── Receive fd via SCM_RIGHTS from UDS ──────────────────────────────────────
static int recv_usb_fd(const char *uds_path)
{
    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    CHECK(sock >= 0, "socket() for UDS client");

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, uds_path, sizeof(addr.sun_path) - 1);

    CHECK(connect(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0,
          "connect() to UDS server");

    // Receive the fd via SCM_RIGHTS
    char dummy;
    struct iovec iov;
    iov.iov_base = &dummy;
    iov.iov_len = 1;

    union {
        struct cmsghdr cm;
        char control[CMSG_SPACE(sizeof(int))];
    } control_un;

    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control_un.control;
    msg.msg_controllen = sizeof(control_un.control);

    ssize_t n = recvmsg(sock, &msg, 0);
    CHECK(n > 0, "recvmsg(SCM_RIGHTS)");

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    CHECK(cmsg && cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS,
          "SCM_RIGHTS not received");

    int fd;
    memcpy(&fd, CMSG_DATA(cmsg), sizeof(int));
    CHECK(fd >= 0, "received fd is invalid");

    close(sock);
    return fd;
}

// ── USB operations ──────────────────────────────────────────────────────────

static int usb_bulk_transfer(int ep, int direction, unsigned char *buf, int len, int timeout_ms)
{
    struct usbdevfs_bulktransfer bt;
    bt.ep = ep;
    bt.len = len;
    bt.timeout = timeout_ms;
    bt.data = buf;

    int ret = ioctl(usb_fd, USBDEVFS_BULK, &bt);
    return ret;
}

static int usb_control_transfer(int bmRequestType, int bRequest,
                                 int wValue, int wIndex,
                                 unsigned char *buf, int wLength, int timeout_ms)
{
    struct usbdevfs_ctrltransfer ct;
    ct.bRequestType = bmRequestType;
    ct.bRequest = bRequest;
    ct.wValue = wValue;
    ct.wIndex = wIndex;
    ct.wLength = wLength;
    ct.timeout = timeout_ms;
    ct.data = buf;

    int ret = ioctl(usb_fd, USBDEVFS_CONTROL, &ct);
    return ret;
}

static int usb_claim_interface(int iface)
{
    unsigned int ifnum = iface;
    return ioctl(usb_fd, USBDEVFS_CLAIMINTERFACE, &ifnum);
}

static int usb_release_interface(int iface)
{
    unsigned int ifnum = iface;
    return ioctl(usb_fd, USBDEVFS_RELEASEINTERFACE, &ifnum);
}

static int usb_reset(void)
{
    return ioctl(usb_fd, USBDEVFS_RESET, NULL);
}

// ── Help & Usage ────────────────────────────────────────────────────────────
static void usage(const char *prog)
{
    fprintf(stderr,
        "usb_bridge — PRoot-side USB ioctl bridge v1.0\n"
        "\n"
        "USAGE:\n"
        "  %s <uds_path> claim <interface_id>\n"
        "  %s <uds_path> release <interface_id>\n"
        "  %s <uds_path> reset\n"
        "  %s <uds_path> bulk <ep_addr> in <length> [timeout_ms]\n"
        "  %s <uds_path> bulk <ep_addr> out <data_hex> [timeout_ms]\n"
        "  %s <uds_path> control <bmReq> <bReq> <wVal> <wIdx> <wLen> [timeout_ms]\n"
        "  %s <uds_path> control-out <bmReq> <bReq> <wVal> <wIdx> <data_hex> [timeout_ms]\n"
        "\n"
        "UDS_PATH: Unix Domain Socket path exposed by the Android app\n"
        "  (e.g., /data/data/com.linux_core/usb_bridge.sock or /tmp/net_usb.sock)\n"
        "\n"
        "BINARY OUTPUT: bulk IN and control IN output raw bytes to stdout.\n"
        "  Use shell redirection to save:  usb_bridge ... > response.bin\n",
        prog, prog, prog, prog, prog, prog, prog);
    exit(1);
}

// Parse hex string like "7e0f" into bytes. Returns number of bytes written.
static int parse_hex(const char *hex, unsigned char *out, int max_len)
{
    int len = 0;
    while (*hex && len < max_len) {
        unsigned int byte;
        if (sscanf(hex, "%2x", &byte) != 1) break;
        out[len++] = (unsigned char)byte;
        hex += 2;
        // Skip optional spaces
        while (*hex == ' ') hex++;
    }
    return len;
}

// ── Main ────────────────────────────────────────────────────────────────────
int main(int argc, char **argv)
{
    if (argc < 3) usage(argv[0]);

    const char *uds_path = argv[1];
    const char *cmd = argv[2];

    // Connect and receive USB fd
    usb_fd = recv_usb_fd(uds_path);
    fprintf(stderr, "[usb_bridge] Received USB fd=%d\n", usb_fd);

    int timeout_ms = 3000; // default 3s

    if (strcmp(cmd, "claim") == 0) {
        CHECK(argc >= 4, "claim requires interface_id");
        int iface = atoi(argv[3]);
        int ret = usb_claim_interface(iface);
        CHECK(ret >= 0, "USBDEVFS_CLAIMINTERFACE");
        fprintf(stderr, "Claimed interface %d\n", iface);

    } else if (strcmp(cmd, "release") == 0) {
        CHECK(argc >= 4, "release requires interface_id");
        int iface = atoi(argv[3]);
        int ret = usb_release_interface(iface);
        CHECK(ret >= 0, "USBDEVFS_RELEASEINTERFACE");
        fprintf(stderr, "Released interface %d\n", iface);

    } else if (strcmp(cmd, "reset") == 0) {
        int ret = usb_reset();
        CHECK(ret >= 0, "USBDEVFS_RESET");
        fprintf(stderr, "Device reset\n");

    } else if (strcmp(cmd, "bulk") == 0) {
        CHECK(argc >= 6, "bulk requires ep_addr, in|out, length|hex_data [timeout_ms]");
        int ep = (int)strtol(argv[3], NULL, 0); // allow 0x81 etc
        const char *dir = argv[4];

        if (argc >= 7) timeout_ms = atoi(argv[6]);

        if (strcmp(dir, "in") == 0 || strcmp(dir, "IN") == 0) {
            int len = atoi(argv[5]);
            CHECK(len > 0 && len <= 16*1024*1024, "invalid length (1..16M)");
            unsigned char *buf = malloc(len);
            CHECK(buf, "malloc failed");

            int ret = usb_bulk_transfer(ep, 0, buf, len, timeout_ms);
            if (ret < 0) {
                fprintf(stderr, "BULK IN ep=0x%02x failed: ret=%d errno=%d\n", ep, ret, errno);
                free(buf);
                exit(1);
            }
            // Output raw binary to stdout
            fwrite(buf, 1, ret, stdout);
            fflush(stdout);
            free(buf);
            fprintf(stderr, "BULK IN ep=0x%02x: %d bytes, timeout=%dms\n", ep, ret, timeout_ms);

        } else if (strcmp(dir, "out") == 0 || strcmp(dir, "OUT") == 0) {
            // Read hex data from argv
            const char *hex = argv[5];
            int max_len = strlen(hex) / 2 + 1;
            unsigned char *buf = malloc(max_len);
            CHECK(buf, "malloc failed");
            int len = parse_hex(hex, buf, max_len);
            if (len == 0) {
                fprintf(stderr, "No hex data provided\n");
                free(buf);
                exit(1);
            }

            int ret = usb_bulk_transfer(ep, 1, buf, len, timeout_ms);
            if (ret < 0) {
                fprintf(stderr, "BULK OUT ep=0x%02x failed: ret=%d errno=%d\n", ep, ret, errno);
                free(buf);
                exit(1);
            }
            free(buf);
            fprintf(stderr, "BULK OUT ep=0x%02x: %d/%d bytes, timeout=%dms\n", ep, ret, len, timeout_ms);

        } else {
            fprintf(stderr, "Unknown direction '%s' — use 'in' or 'out'\n", dir);
            exit(1);
        }

    } else if (strcmp(cmd, "control") == 0) {
        CHECK(argc >= 8, "control requires bmReq, bReq, wVal, wIdx, wLen [timeout_ms]");
        int bmReq = (int)strtol(argv[3], NULL, 0);
        int bReq  = (int)strtol(argv[4], NULL, 0);
        int wVal  = (int)strtol(argv[5], NULL, 0);
        int wIdx  = (int)strtol(argv[6], NULL, 0);
        int wLen  = atoi(argv[7]);

        if (argc >= 9) timeout_ms = atoi(argv[8]);

        unsigned char *buf = calloc(1, wLen > 0 ? wLen : 1);
        CHECK(buf, "malloc failed");

        int ret = usb_control_transfer(bmReq, bReq, wVal, wIdx, buf, wLen, timeout_ms);
        if (ret < 0) {
            fprintf(stderr, "CONTROL failed: ret=%d errno=%d\n", ret, errno);
            free(buf);
            exit(1);
        }
        if (wLen > 0 && ret > 0) {
            fwrite(buf, 1, ret, stdout);
            fflush(stdout);
        }
        free(buf);
        fprintf(stderr, "CONTROL bmReq=0x%02x bReq=0x%02x: %d bytes, timeout=%dms\n",
                bmReq, bReq, ret, timeout_ms);

    } else if (strcmp(cmd, "control-out") == 0) {
        CHECK(argc >= 8, "control-out requires bmReq, bReq, wVal, wIdx, data_hex [timeout_ms]");
        int bmReq = (int)strtol(argv[3], NULL, 0);
        int bReq  = (int)strtol(argv[4], NULL, 0);
        int wVal  = (int)strtol(argv[5], NULL, 0);
        int wIdx  = (int)strtol(argv[6], NULL, 0);
        const char *hex = argv[7];

        if (argc >= 9) timeout_ms = atoi(argv[8]);

        int max_len = strlen(hex) / 2 + 1;
        unsigned char *buf = malloc(max_len);
        CHECK(buf, "malloc failed");
        int len = parse_hex(hex, buf, max_len);

        int ret = usb_control_transfer(bmReq, bReq, wVal, wIdx, buf, len, timeout_ms);
        if (ret < 0) {
            fprintf(stderr, "CONTROL-OUT failed: ret=%d errno=%d\n", ret, errno);
            free(buf);
            exit(1);
        }
        free(buf);
        fprintf(stderr, "CONTROL-OUT bmReq=0x%02x bReq=0x%02x: %d bytes, timeout=%dms\n",
                bmReq, bReq, ret, timeout_ms);

    } else {
        fprintf(stderr, "Unknown command: %s\n", cmd);
        usage(argv[0]);
    }

    close(usb_fd);
    return 0;
}
