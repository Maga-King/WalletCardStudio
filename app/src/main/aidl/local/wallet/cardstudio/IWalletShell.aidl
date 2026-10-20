package local.wallet.cardstudio;

interface IWalletShell {
    void destroy() = 16777114;
    String execute(String script) = 1;
}
