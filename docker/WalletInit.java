/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
import io.xdag.Wallet;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.bip.Bip39Mnemonic;

/**
 * Headless wallet for a devnet container. The CLI asks for a password twice and then
 * asks the operator to retype the mnemonic, which a container has no console for.
 */
public final class WalletInit {

    private WalletInit() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: WalletInit <rootDir>");
        }
        String password = System.getenv("XDAGJ_WALLET_PASSWORD");
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("XDAGJ_WALLET_PASSWORD is empty");
        }
        DevnetConfig config = new DevnetConfig();
        config.changePara(new String[] {"-f", args[0]});
        config.setDir();
        Wallet wallet = new Wallet(config);
        if (wallet.exists()) {
            if (!wallet.unlock(password)) {
                throw new IllegalStateException("existing wallet rejected the password");
            }
            System.out.println("wallet ready");
            return;
        }
        if (!wallet.unlock(password) || !wallet.flush()) {
            throw new IllegalStateException("wallet create failed");
        }
        wallet.initializeHdWallet(Bip39Mnemonic.generateString());
        wallet.addAccountWithNextHdKey();
        if (!wallet.flush()) {
            throw new IllegalStateException("wallet flush failed");
        }
        System.out.println("wallet ready");
    }
}
