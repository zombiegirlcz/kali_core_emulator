# Sourced from zshrc to enable the bundled plugins.
# Paths are relative to the host prefix (set PREFIX accordingly).
: ${PREFIX:=/data/user/0/com.linux_core/files/usr}

# completions
fpath=(
  $PREFIX/share/zsh-completions
  $PREFIX/share/zsh/site-functions
  $PREFIX/share/zsh/functions
  $fpath
)

# syntax highlighting must be loaded before autosuggestions
if [ -f $PREFIX/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ]; then
  source $PREFIX/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh
fi
if [ -f $PREFIX/share/zsh-autosuggestions/zsh-autosuggestions.zsh ]; then
  source $PREFIX/share/zsh-autosuggestions/zsh-autosuggestions.zsh
  ZSH_AUTOSUGGEST_HIGHLIGHT_STYLE='fg=244'
fi
if [ -f $PREFIX/share/zsh-history-substring-search/zsh-history-substring-search.zsh ]; then
  source $PREFIX/share/zsh-history-substring-search/zsh-history-substring-search.zsh
  bindkey '^[[A' history-substring-search-up
  bindkey '^[[B' history-substring-search-down
fi
