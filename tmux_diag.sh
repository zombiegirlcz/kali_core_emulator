#!/bin/sh
# Spusť v terminálu APLIKACE (kde tmux selže) i v Termuxu pro porovnání
echo "===== TERMINAL DIAG ====="
echo "tty:        $(tty 2>&1)"
echo "fd0 -> $(readlink /proc/self/fd/0 2>&1)"
echo "fd1 -> $(readlink /proc/self/fd/1 2>&1)"
echo "SID/PGID:   $(ps -o pid,ppid,sid,pgid,tty,comm -p $$ 2>/dev/null || echo '?')"
echo "--- /dev/tty test ---"
if exec 9<>/dev/tty 2>/dev/null; then echo "/dev/tty OPEN ok"; exec 9<&-; else echo "/dev/tty FAIL ($?)"; fi
echo "--- controlling tty (TIOCGSID) ---"
python3 -c "
import os,fcntl,termios,struct
for fd in (0,):
  try:
    b=fcntl.ioctl(fd,termios.TIOCGSID,struct.pack('i',0)); print('TIOCGSID(fd%d)='%fd, struct.unpack('i',b)[0])
  except Exception as e: print('TIOCGSID fail:',e)
try:
  fd=os.open('/dev/tty',os.O_RDWR); print('open(/dev/tty)=',fd,'isatty=',os.isatty(fd)); os.close(fd)
except Exception as e: print('open(/dev/tty) FAIL:',e)
"
echo "--- tmux verbose ---"
tmux -f /dev/null new-session -d -s diag 'echo inside; sleep 1' 2>&1; echo "tmux new-session rc=$?"
tmux -f /dev/null ls 2>&1; echo "tmux ls rc=$?"
tmux -f /dev/null kill-server 2>/dev/null
echo "===== END ====="
