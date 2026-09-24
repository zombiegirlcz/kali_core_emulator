#!/system/bin/sh
# NetHunter Kali Core Emulator Bootstrap Script
# This script runs inside the PRoot guest environment during first boot

set -e

# Print colored output if available
if command -v tput >/dev/null 2>&1; then
    GREEN=$(tput setaf 2) || GREEN=""
    YELLOW=$(tput setaf 3) || YELLOW=""
    RED=$(tput setaf 1) || RED=""
    NC=$(tput sgr0) || NC=""
else
    GREEN=""
    YELLOW=""
    RED=""
    NC=""
fi

log() {
    echo "${GREEN}[+]${NC} $1"
}

error() {
    echo "${RED}[!]${NC} $1"
}

warn() {
    echo "${YELLOW}[!]${NC} $1"
}

# Ensure we're root
if [ "$(id -u)" -ne 0 ]; then
    error "This script must be run as root"
    exit 1
fi

log "Starting NetHunter Kali Core Emulator bootstrap..."

# Create essential directories
log "Creating essential system directories..."
mkdir -p /tmp /var/tmp /usr/local/bin /usr/local/share

# Set up PATH
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# Set default locale
export LANG=C.UTF-8
export LC_ALL=C.UTF-8

# Disable systemd controls by redirecting to /bin/true
log "Disabling systemd controls to prevent service configuration issues..."
for cmd in systemctl service update-rc.d invoke-rc.d dpkg-preconfigure setcap sysctl udevadm modprobe dmidecode systemd-detect-virt resolvconf dpkg-realpath systemd-sysusers systemd-tmpfiles journalctl; do
    if [ ! -x "/usr/local/bin/$cmd" ] && [ ! -x "/usr/bin/$cmd" ]; then
        ln -sf /bin/true "/usr/local/bin/$cmd"
    fi
done

# Reinforce diversions for systemd tools if missing
log "Reinforcing diversions for systemd tools..."
for cmd in systemd-sysusers systemd-tmpfiles journalctl systemctl; do
    if [ ! -x "/usr/local/bin/$cmd" ] && [ ! -x "/usr/bin/$cmd" ]; then
        ln -sf /bin/true "/usr/local/bin/$cmd"
    fi
done

# Install and configure essential packages with performance optimizations
log "Installing and configuring essential packages..."

# Configure dpkg with write optimizations to bypass Android storage limitations
if [ -f /usr/bin/dpkg ]; then
    echo "force-unsafe-io" > /etc/dpkg/dpkg.cfg.d/01_force_unsafe_io
    log "Configured dpkg with force-unsafe-io optimization"
fi

# Configure apt with write optimizations
if [ -f /usr/bin/apt-get ]; then
    echo "force-unsafe-io" > /etc/apt/apt.conf.d/99_force_unsafe_io
    echo "Acquire::http::Keep-alive true;" >> /etc/apt/apt.conf.d/99_keepalive
    echo "Acquire::http::Timeout '30';" >> /etc/apt/apt.conf.d/99_timeout
    echo "Acquire::ftp::Timeout '30';" >> /etc/apt/apt.conf.d/99_timeout
    echo "Dir::Cache::archives '/tmp/apt/archives';" >> /etc/apt/apt.conf.d/99_temp
    log "Configured apt with performance optimizations"
fi

# Create /tmp/apt directory structure
mkdir -p /tmp/apt/archives
chmod 755 /tmp/apt

# Create mandatory directories for system operation
log "Creating mandatory system directories..."
mkdir -p /root /home/kali /home/parrot /usr/local /var/log
chmod 755 /root /home

# Set up default users
if [ ! -d /home/kali ]; then
    mkdir -p /home/kali
    chmod 755 /home/kali
    useradd -m -s /bin/zsh kali 2>/dev/null || useradd -m -s /bin/bash kali
    # NetHunter: heslo se NEONastavuje automaticky — automaticky nasazene (nezname)
    # heslo se smaze (passwd -d / usermod -p ""), aby slo rovnou nastavit nove.
    chmod 600 /etc/shadow /etc/gshadow 2>/dev/null || true
    passwd -d kali 2>/dev/null || true
    usermod -p "" kali 2>/dev/null || true
    log "Created default user 'kali' (bez automatickeho hesla)"
fi

if [ ! -d /home/parrot ]; then
    mkdir -p /home/parrot
    chmod 755 /home/parrot
    useradd -m -s /bin/zsh parrot 2>/dev/null || useradd -m -s /bin/bash parrot
    # NetHunter: bez automatickeho hesla (viz vyse kali)
    chmod 600 /etc/shadow /etc/gshadow 2>/dev/null || true
    passwd -d parrot 2>/dev/null || true
    usermod -p "" parrot 2>/dev/null || true
    log "Created default user 'parrot' (bez automatickeho hesla)"
fi

# Fix broken packages and resolve dependencies
log "Fixing broken packages and resolving dependencies..."
if command -v dpkg >/dev/null 2>&1; then
    # Fix broken dpkg installations
    dpkg --configure -a || true
    # Remove unnecessary dependencies
    apt-get -f install -y || true
    # Clean up
    apt-get clean || true
    apt-get autoremove -y || true
    log "Package maintenance completed"
fi

# Install essential packages
log "Installing essential packages..."
ESSENTIAL_PACKAGES="procps psmisc sudo curl wget git net-tools iproute2 dnsutils unzip tar gzip bzip2 xz-utils less vim nano bash zsh python3 python3-pip python3-dev git"

for pkg in $ESSENTIAL_PACKAGES; do
    if ! dpkg -l | grep -q "^ii  $pkg "; then
        log "Installing $pkg..."
        apt-get install -y --no-install-recommends "$pkg" || warn "Failed to install $pkg"
    else
        log "$pkg already installed"
    fi
done

# Configure sudo for passwordless use
if command -v sudo >/dev/null 2>&1; then
    log "Configuring sudo for passwordless use..."
    echo "kali ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/kali
    echo "parrot ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/parrot
    chmod 440 /etc/sudoers.d/kali /etc/sudoers.d/parrot
    log "Sudo configured for passwordless use"
fi

# Set up bash/zsh configurations
log "Setting up shell configurations..."

# Create basic .bashrc if missing
if [ ! -f /root/.bashrc ]; then
    cat > /root/.bashrc << 'EOF'
export PATH="$HOME/.local/bin:$PATH"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export PS1="\u@\h:\w\$ "
alias ls="ls --color=auto"
alias ll="ls -la"
alias la="ls -a"
alias rm="rm -i"
EOF
    log "Created /root/.bashrc"
fi

# Create basic .zshrc for kali if missing
if [ ! -f /home/kali/.zshrc ]; then
    cat > /home/kali/.zshrc << 'EOF'
zsh
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
autoload -Uz compinit
compinit -u
bindkey -v
bindkey "^P" up-history
bindkey "^N" down-history
aliasshow() { sed -n 's/^alias *//p' ~/.zshrc | sed -n '/^alias /p' }
EOF
    log "Created /home/kali/.zshrc"
fi

# Create basic .zshrc for parrot if missing
if [ ! -f /home/parrot/.zshrc ]; then
    cat > /home/parrot/.zshrc << 'EOF'
zsh
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
autoload -Uz compinit
compinit -u
bindkey -v
bindkey "^P" up-history
bindkey "^N" down-history
aliasshow() { sed -n 's/^alias *//p' ~/.zshrc | sed -n '/^alias /p' }
EOF
    log "Created /home/parrot/.zshrc"
fi

# Set correct permissions for user homes
log "Setting permissions for user homes..."
chmod 755 /home
chmod 750 /home/kali /home/parrot
chmod 700 /root

# Create NetHunter specific directories and configurations
log "Creating NetHunter specific configurations..."
mkdir -p /etc/nethunter
mkdir -p /var/log/nethunter

# Create basic NetHunter configuration
cat > /etc/nethunter/config << 'EOF'
# NetHunter Configuration
export NETHUNTER_HOME="/opt/nethunter"
export PATH="/opt/nethunter/bin:$PATH"
export NETHUNTER_VERSION="2.0"
EOF

# Set up NetHunter database (simplified)
log "Setting up NetHunter database..."
mkdir -p /opt/nethunter/data
cat > /opt/nethunter/data/.nethunter << 'EOF'
VERSION=2.0
INSTALL_DATE=$(date +%Y-%m-%d)
EOF

# Create basic NetHunter shell completion
log "Setting up NetHunter shell completion..."
cat > /etc/bash_completion.d/nethunter << 'EOF'
_nethunter_completion() {
    local cur prev opts
    COMPREPLY=()
    cur="${COMP_WORDS[COMP_CWORD]}"
    prev="${COMP_WORDS[COMP_CWORD-1]}"
    opts="--help --version"
    COMPREPLY=( $(compgen -W "$opts" -- "$cur") )
}
complete -F _nethunter_completion nh
EOF

# Clean up temporary files
log "Cleaning up temporary files..."
rm -f /root/.bash_history
rm -f /home/kali/.bash_history
rm -f /home/parrot/.bash_history
rm -rf /tmp/* /var/tmp/*

# Final checks
log "Performing final system checks..."

# Check for essential binaries
for bin in ls cat echo python3 curl wget sudo; do
    if command -v $bin >/dev/null 2>&1; then
        log "✓ $bin is available"
    else
        warn "✗ $bin is not available"
    fi
done

# Check user accounts
if id kali >/dev/null 2>&1; then
    log "✓ User 'kali' exists"
else
    warn "✗ User 'kali' does not exist"
fi

if id parrot >/dev/null 2>&1; then
    log "✓ User 'parrot' exists"
else
    warn "✗ User 'parrot' does not exist"
fi

log "Bootstrap completed successfully!"
log "You can now access the NetHunter environment by running 'su kali' or 'su parrot'"
log "For assistance, run 'nh list' or 'man nh' for documentation on the unified NetHunter CLI tool."

exit 0