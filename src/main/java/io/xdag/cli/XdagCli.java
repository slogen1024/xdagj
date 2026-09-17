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

package io.xdag.cli;

import com.google.common.collect.Lists;
import io.xdag.Kernel;
import io.xdag.Launcher;
import io.xdag.Wallet;
import io.xdag.chain.l1.ChainL1SnapshotGate;
import io.xdag.chain.l1.ChainL1Store;
import io.xdag.chain.repair.ChainRepairTool;
import io.xdag.config.Config;
import io.xdag.config.Constants;
import io.xdag.core.BlockchainImpl;
import io.xdag.crypto.bip.Bip39Mnemonic;
import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.keys.AddressUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.AddressStore;
import io.xdag.db.BlockStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.SnapshotStore;
import io.xdag.db.rocksdb.AddressStoreImpl;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.db.rocksdb.RocksdbKVSource;
import io.xdag.db.rocksdb.SnapshotStoreImpl;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import org.apache.commons.lang3.Strings;

import static io.xdag.crypto.keys.AddressUtils.toBytesAddress;
import static io.xdag.utils.WalletUtils.WALLET_PASSWORD_PROMPT;

public class XdagCli extends Launcher {

    private static final Scanner scanner = new Scanner(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    /**
     * Creates a new Xdag CLI instance.
     */
    public XdagCli() {
        Option helpOption = Option.builder()
                .longOpt(XdagOption.HELP.toString())
                .desc("print help")
                .build();
        addOption(helpOption);

        Option versionOption = Option.builder()
                .longOpt(XdagOption.VERSION.toString())
                .desc("show version")
                .build();
        addOption(versionOption);

        Option accountOption = Option.builder()
                .longOpt(XdagOption.ACCOUNT.toString())
                .desc("init|create|list")
                .hasArg(true).numberOfArgs(1).optionalArg(false).argName("action").type(String.class)
                .build();
        addOption(accountOption);

        Option changePasswordOption = Option.builder()
                .longOpt(XdagOption.CHANGE_PASSWORD.toString()).desc("change wallet password").build();
        addOption(changePasswordOption);

        Option dumpPrivateKeyOption = Option.builder()
                .longOpt(XdagOption.DUMP_PRIVATE_KEY.toString())
                .desc("print hex key")
                .hasArg(true).optionalArg(false).argName("address").type(String.class)
                .build();
        addOption(dumpPrivateKeyOption);

        Option importPrivateKeyOption = Option.builder()
                .longOpt(XdagOption.IMPORT_PRIVATE_KEY.toString())
                .desc("import hex key")
                .hasArg(true).optionalArg(false).argName("key").type(String.class)
                .build();
        addOption(importPrivateKeyOption);

        Option importMnemonicOption = Option.builder()
                .longOpt(XdagOption.IMPORT_MNEMONIC.toString())
                .desc("import HDWallet mnemonic")
                .hasArg(true).optionalArg(false).argName("mnemonic").type(String.class)
                .build();
        addOption(importMnemonicOption);

        Option convertOldWalletOption = Option.builder()
                .longOpt(XdagOption.CONVERT_OLD_WALLET.toString())
                .desc("convert xdag old wallet.dat to private key hex")
                .hasArg(true).optionalArg(false).argName("filename").type(String.class)
                .build();
        addOption(convertOldWalletOption);

        Option bootSnapshotOption = Option.builder()
                .longOpt(XdagOption.ENABLE_SNAPSHOT.toString()).desc("enable snapshot")
                .hasArg(true).numberOfArgs(3).optionalArg(false)
                .argName("isSnapshotJ").type(Boolean.class)
                .argName("snapshotheight").type(Integer.class)
                .argName("snapshottime").type(Integer.class)
                .desc("the parameter snapshottime uses hexadecimal")
                .build();
        addOption(bootSnapshotOption);

        Option makeSnapshotOption = Option.builder()
                .longOpt(XdagOption.MAKE_SNAPSHOT.toString()).desc("make snapshot")
                .hasArg(true).optionalArg(true).argName("covertuint").type(String.class)
                .build();
        addOption(makeSnapshotOption);

        Option repairChainOption = Option.builder()
                .longOpt(XdagOption.REPAIR_CHAIN.toString())
                .desc("unwind to the last complete main height; mode = dry-run|force|reinit-marker; "
                        + "dry-run may still initialize an absent completion marker")
                .hasArg(true).optionalArg(true).argName("mode").type(String.class)
                .build();
        addOption(repairChainOption);
    }

    public static void main(String[] args, XdagCli cli) throws Exception {
        try {
            cli.start(args);
        } catch (IOException exception) {
            System.err.println(exception.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        main(args, new XdagCli());
    }

    public void start(String[] args) throws Exception {
        Config config = buildConfig(args);
        setConfig(config);
        // move old args
        List<String> argsList = Lists.newArrayList();
        for (String arg : args) {
            if (Strings.CS.equalsAny(arg, "-d", "-t")) {
                // only devnet or testnet
            } else {
                argsList.add(arg);
            }
        }
        String[] newArgs = argsList.toArray(new String[0]);
        // parse common options
        CommandLine cmd = null;
        try {
            cmd = parseOptions(newArgs);
        } catch (ParseException exception) {
            System.err.println("Parsing Failed:" + exception.getMessage());
        }

        assert cmd != null;
        if (cmd.hasOption(XdagOption.HELP.toString())) {
            printHelp();
        } else if (cmd.hasOption(XdagOption.VERSION.toString())) {
            printVersion();
        } else if (cmd.hasOption(XdagOption.ACCOUNT.toString())) {
            String action = cmd.getOptionValue(XdagOption.ACCOUNT.toString()).trim();
            switch (action) {
                case "init" -> initHDAccount();
                case "create" -> createAccount();
                case "list" -> listAccounts();
                default -> System.out.println("No Action!");
            }
        } else if (cmd.hasOption(XdagOption.CHANGE_PASSWORD.toString())) {
            changePassword();
        } else if (cmd.hasOption(XdagOption.DUMP_PRIVATE_KEY.toString())) {
            dumpPrivateKey(cmd.getOptionValue(XdagOption.DUMP_PRIVATE_KEY.toString()).trim());
        } else if (cmd.hasOption(XdagOption.IMPORT_PRIVATE_KEY.toString())) {
            importPrivateKey(cmd.getOptionValue(XdagOption.IMPORT_PRIVATE_KEY.toString()).trim());
        } else if (cmd.hasOption(XdagOption.IMPORT_MNEMONIC.toString())) {
            importMnemonic(cmd.getOptionValue(XdagOption.IMPORT_MNEMONIC.toString()).trim());
        } else if (cmd.hasOption(XdagOption.MAKE_SNAPSHOT.toString())) {
            boolean convertXAmount = false;
            String action = cmd.getOptionValue(XdagOption.MAKE_SNAPSHOT.toString());
            if (action != null && action.trim().equals("convertxamount")) {
                convertXAmount = true;
            }
            makeSnapshot(convertXAmount);
        } else if (cmd.hasOption(XdagOption.REPAIR_CHAIN.toString())) {
            // Never falls through to start(): the whole point of the command is that the node must
            // not run on this store until the repair has been accepted.
            int code = repairChain(cmd.getOptionValue(XdagOption.REPAIR_CHAIN.toString()));
            if (code != 0) {
                exit(code);
            }
        } else {
            if (cmd.hasOption(XdagOption.ENABLE_SNAPSHOT.toString())) {
                String[] values = cmd.getOptionValues(XdagOption.ENABLE_SNAPSHOT.toString().trim());
                try {
                    boolean isSnapshotJ = Boolean.parseBoolean(values[0]);
                    long height = Long.parseLong(values[1]);
                    long time = Long.parseLong(values[2], 16);
                    config.getSnapshotSpec().setSnapshotJ(isSnapshotJ);
                    config.getSnapshotSpec().setSnapshotHeight(height);
                    config.getSnapshotSpec().setSnapshotTime(time);
                    config.getSnapshotSpec().snapshotEnable();
                    System.out.println("enable snapshot:" + config.getSnapshotSpec().isSnapshotEnabled());
                } catch (NumberFormatException e) {
                    System.out.println("params error");
                }
            }
            start();
        }
    }

    protected void printHelp() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setWidth(200);
        formatter.printHelp("./xdag.sh [options]", getOptions());
    }

    protected void printVersion() {
        System.out.println(Constants.CLIENT_VERSION);
    }

    protected void start() throws IOException {
        // create/unlock wallet
        Wallet wallet = loadWallet().exists() ? loadAndUnlockWallet() : createNewWallet();
        if (wallet == null) {
            return;
        }

        if (!wallet.isHdWalletInitialized()) {
            initializedHdSeed(wallet, System.out);
        }

        // create a new account if the wallet is empty
        List<ECKeyPair> accounts = wallet.getAccounts();
        if (accounts.isEmpty()) {
            ECKeyPair key = wallet.addAccountWithNextHdKey();
            wallet.flush();
            System.out.println("New Address (Hex):" + BytesUtils.toHexString(toBytesAddress(key).toArray()));
            System.out.println("New Address (Base58):" + Base58.encodeCheck(toBytesAddress(key)));
        }

        // start kernel
        try {
            startKernel(getConfig(), wallet);
        } catch (Exception e) {
            System.err.println("Uncaught exception during kernel startup:" + e.getMessage());
            e.printStackTrace();
            exit(-1);
        }
    }

    /**
     * Starts the kernel.
     */
    protected Kernel startKernel(Config config, Wallet wallet) {
        Kernel kernel = new Kernel(config, wallet);
        kernel.testStart();
        return kernel;
    }

    protected void initHDAccount() {
        // create/unlock wallet
        Wallet wallet;
        if (loadWallet().exists()) {
            wallet = loadAndUnlockWallet();
        } else {
            wallet = createNewWallet();
        }

        if (wallet == null) {
            return;
        }
        if (!wallet.isHdWalletInitialized()) {
            initializedHdSeed(wallet, System.out);
        } else {
            System.out.println("HD Wallet Account already init.");
        }
    }

    protected void createAccount() {
        Wallet wallet = loadAndUnlockWallet();
        if (Objects.nonNull(wallet) && !wallet.isHdWalletInitialized()) {
            System.out.println("Please init HD Wallet account first!");
            return;
        }
        ECKeyPair key = wallet.addAccountWithNextHdKey();
        if (wallet.flush()) {
            System.out.println("New Address:" + AddressUtils.toBase58Address(key));
            System.out.println("PublicKey:" + key.getPublicKey().toUnprefixedHex());
        }
    }

    protected void listAccounts() {
        Wallet wallet = loadAndUnlockWallet();
        List<ECKeyPair> accounts = wallet.getAccounts();

        if (accounts.isEmpty()) {
            System.out.println("Account Missing");
        } else {
            for (int i = 0; i < accounts.size(); i++) {
                System.out.println("Address:" + i + " " + AddressUtils.toBase58Address(accounts.get(i)));
            }
        }
    }

    protected void changePassword() {
        Wallet wallet = loadAndUnlockWallet();
        if (wallet.isUnlocked()) {
            String newPassword = readNewPassword("EnterNewPassword:", "ReEnterNewPassword:");
            if (newPassword == null) {
                return;
            }
            wallet.changePassword(newPassword);
            boolean isFlushed = wallet.flush();
            if (!isFlushed) {
                System.out.println("Wallet File Cannot Be Updated");
                return;
            }
            System.out.println("Password Changed Successfully!");
        }
    }

    protected void exit(int code) {
        System.exit(code);
    }

    protected void dumpPrivateKey(String address) {
        Wallet wallet = loadAndUnlockWallet();
        byte[] addressBytes = BytesUtils.hexStringToBytes(address);
        ECKeyPair account = wallet.getAccount(addressBytes);
        if (account == null) {
            System.out.println("Address Not In Wallet");
        } else {
            System.out.println("Private:" + account.getPrivateKey().toUnprefixedHex());
        }
        System.out.println("Private Dump Successfully!");
    }

    protected boolean importPrivateKey(String key) {
        Wallet wallet = loadWallet().exists() ? loadAndUnlockWallet() : createNewWallet();
        ECKeyPair account = ECKeyPair.fromHex(key);

        boolean accountAdded = wallet.addAccount(account);
        if (!accountAdded) {
            System.out.println("Private Key Already In Wallet");
            return false;
        }

        boolean walletFlushed = wallet.flush();
        if (!walletFlushed) {
            System.out.println("Wallet File Cannot Be Updated");
            return false;
        }

        System.out.println("Address:" + AddressUtils.toBase58Address(account));
        System.out.println("PublicKey:" + account.getPublicKey().toUnprefixedHex());
        System.out.println("Private Key Imported Successfully!");
        return true;
    }

    protected boolean importMnemonic(String mnemonic) {
        Wallet wallet = loadWallet().exists() ? loadAndUnlockWallet() : createNewWallet();

        if (wallet.isHdWalletInitialized()) {
            System.out.println("HDWallet Mnemonic Already In Wallet");
            return false;
        }

        if (!Bip39Mnemonic.isValid(mnemonic)) {
            System.out.println("Wrong Mnemonic");
            return false;
        }

        wallet.initializeHdWallet(mnemonic);
        if (!wallet.flush()) {
            System.out.println("HDWallet File Cannot Be Updated");
            return false;
        }

        // default add one hd key
        createAccount();

        System.out.println("HDWallet Mnemonic Imported Successfully!");
        return true;
    }

    public Wallet loadWallet() {
        return new Wallet(getConfig());
    }

    public Wallet loadAndUnlockWallet() {
        Wallet wallet = loadWallet();
        if (getPassword() == null) {
            if (wallet.unlock("")) {
                setPassword("");
            } else {
                setPassword(readPassword(WALLET_PASSWORD_PROMPT));
            }
        }

        if (!wallet.unlock(getPassword())) {
            System.err.println("Invalid password");
        }

        return wallet;
    }

    /**
     * Create a new wallet with a new password
     */
    public Wallet createNewWallet() {
        System.out.println("Create New Wallet...");
        String newPassword = readNewPassword("EnterNewPassword:", "ReEnterNewPassword:");
        if (newPassword == null) {
            return null;
        }

        setPassword(newPassword);
        Wallet wallet = loadWallet();

        if (!wallet.unlock(newPassword) || !wallet.flush()) {
            System.err.println("Create New WalletError");
            System.exit(-1);
            return null;
        }

        return wallet;
    }

    /**
     * Read a new password from input and require confirmation
     */
    public String readNewPassword(String newPasswordMessageKey, String reEnterNewPasswordMessageKey) {
        String newPassword = readPassword(newPasswordMessageKey);
        String newPasswordRe = readPassword(reEnterNewPasswordMessageKey);

        if (!newPassword.equals(newPasswordRe)) {
            System.err.println("ReEnter NewPassword Incorrect");
            System.exit(-1);
            return null;
        }

        return newPassword;
    }

    /**
     * Reads a line from the console.
     */
    public String readLine(String prompt) {
        if (prompt != null) {
            System.out.print(prompt);
            System.out.flush();
        }

        return scanner.nextLine();
    }

    public boolean initializedHdSeed(Wallet wallet, PrintStream printer) {
        if (wallet.isUnlocked() && !wallet.isHdWalletInitialized()) {
            // HD Mnemonic
            printer.println("HdWallet Initializing...");
            try {
                String phrase = Bip39Mnemonic.generateString();
                printer.println("HdWallet Mnemonic:" + phrase);

                String repeat = readLine("HdWallet Mnemonic Repeat:");
                repeat = String.join(" ", repeat.trim().split("\\s+"));

                if (!repeat.equals(phrase)) {
                    printer.println("HdWallet Initialized Failure");
                    return false;
                }

                wallet.initializeHdWallet(phrase);
                wallet.flush();
                printer.println("HdWallet Initialized Successfully!");
                return true;
            } catch (Exception e) {
                printer.println("HdWallet Initialization Failed: " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    public String readPassword(String prompt) {
        Console console = System.console();
        if (console == null) {
            if (prompt != null) {
                System.out.print(prompt);
                System.out.flush();
            }
            return scanner.nextLine();
        }
        return new String(console.readPassword(prompt));
    }

    public void makeSnapshot(boolean b) {
        System.out.println("make snapshot start");
        System.out.println("convertXAmount = " + b);
        long start = System.currentTimeMillis();
        this.getConfig().getSnapshotSpec().setSnapshotJ(true);
        // TIME, not BLOCK: the raw blocks a node wrote are in the database NAMED TIME — the same
        // node layout BlockStoreImpl.forNode encodes. This source is the snapshot's block source.
        RocksdbKVSource blockSource = new RocksdbKVSource(DatabaseName.TIME.toString());
        blockSource.setConfig(getConfig());
        blockSource.init();
        RocksdbKVSource snapshotSource = new RocksdbKVSource("SNAPSHOT/BLOCKS");
        snapshotSource.setConfig(getConfig());
        snapshotSource.init();
        RocksdbKVSource indexSource = new RocksdbKVSource(DatabaseName.INDEX.toString());
        indexSource.setConfig(getConfig());
        indexSource.init();
        SnapshotStore snapshotStore = new SnapshotStoreImpl(snapshotSource);

        snapshotStore.makeSnapshot(blockSource,indexSource,b);

        Path source = Paths.get(getConfig().getRootDir() + "/rocksdb/xdagdb/ADDRESS");
        Path target = Paths.get(getConfig().getRootDir() + "/rocksdb/xdagdb/SNAPSHOT/ADDRESS");
        copyDir(source.toString(),target.toString());

        // Chain contracts (SP0a): carry CHAIN_L1 alongside SNAPSHOT/BLOCKS and SNAPSHOT/ADDRESS.
        // Always exported: an "empty" CHAIN_L1 snapshot is one META key plus its hash, and a node
        // below the chain activation height ignores the directory anyway.
        RocksdbKVSource chainSource = new RocksdbKVSource(DatabaseName.CHAIN_L1.toString());
        chainSource.setConfig(getConfig());
        ChainL1Store chainStore = new ChainL1Store(chainSource);
        String chainFailure = null;
        try {
            chainStore.start();
            ChainL1SnapshotGate.export(getConfig(), chainStore);
            System.out.println("chain state snapshot written to " + ChainL1SnapshotGate.snapshotDir(getConfig()));
        } catch (RuntimeException e) {
            // Every runtime failure, not just IllegalStateException: chainStore.start() opens a
            // RocksDB column family and surfaces a failure to do so as a plain RuntimeException.
            // The block/address snapshot is already written: report the chain failure but still
            // print the height and next start frame the operator needs.
            // e, not e.getMessage(): a RocksDB failure may carry no message at all.
            chainFailure = "chain state snapshot NOT written: " + e;
            System.out.println(chainFailure);
        } finally {
            chainStore.stop();
        }

        long end = System.currentTimeMillis();
        System.out.println("make snapshot done");
        System.out.println("time：" + (end - start) + "ms");
        System.out.println("snapshot height: " + snapshotStore.getHeight());
        System.out.println("next start frame: " + Long.toHexString(XdagTime.getEndOfEpoch(snapshotStore.getNextTime()) + 1));
        if (chainFailure != null) {
            System.out.println(chainFailure + " -- this snapshot cannot boot a node at or past the chain "
                    + "activation height; fix the cause and export SNAPSHOT/CHAIN_L1 again");
        }
    }

    /**
     * SP0b-1: offline main chain repair. Opens the stores exactly like a node would, builds the
     * blockchain on a kernel in {@link Kernel#enterRepairMode() repair mode} — so the boot
     * consistency check records its report instead of refusing to start, and no check-main loop can
     * confirm a block while the tool works — then hands over to {@link ChainRepairTool}.
     *
     * <p>Modes: {@code dry-run} prints the plan and writes nothing (bar the completion marker the
     * boot itself initializes when the store carries none at all); {@code force} unwinds further
     * below the tip than {@code chain.consistency.window} allows; {@code reinit-marker} adopts the
     * persisted tip as the last complete height without unwinding (the downgrade trap — see
     * {@link ChainRepairTool#reinitMarker}); no argument runs the ordinary repair.
     *
     * <p>Exit codes: 0 the store is startable (clean, repaired, or a dry run that would have
     * proceeded); 1 refused, pass {@code force} (a dry run reports this too); 2 unrepairable from
     * local state — restore from a snapshot; 3 the command could not run at all (no usable wallet,
     * an unknown mode, or an exception).
     *
     * @param mode {@code dry-run}, {@code force}, {@code reinit-marker}, or {@code null}/empty
     * @return the process exit code
     */
    protected int repairChain(String mode) {
        String action = mode == null ? "" : mode.trim();
        boolean dryRun = "dry-run".equals(action);
        boolean force = "force".equals(action);
        boolean reinit = "reinit-marker".equals(action);
        // A typo must not silently become the most destructive of the four: without this, anything
        // that is not "dry-run" or "force" would read as "no argument" and unwind for real.
        if (!action.isEmpty() && !dryRun && !force && !reinit) {
            System.out.println("unknown --repairchain mode '" + action
                    + "'; expected dry-run, force or reinit-marker, or no argument at all");
            return 3;
        }

        Wallet wallet = loadWallet().exists() ? loadAndUnlockWallet() : null;
        if (wallet == null || !wallet.isUnlocked()) {
            System.out.println("wallet not found or locked; --repairchain needs the node wallet");
            return 3;
        }

        Kernel kernel = new Kernel(getConfig(), wallet);
        kernel.enterRepairMode();
        DatabaseFactory dbFactory = new RocksdbFactory(getConfig());
        BlockchainImpl blockchain = null;
        ChainL1Store chainStore = null;
        try {
            // forNode, never the constructor: it is the single definition of the layout a node
            // writes (raw blocks in the database named TIME). Opening by the signature order here
            // would hand the tool the two databases swapped, and every repair would fail on
            // "block data is incomplete" because no raw bytes could be found.
            BlockStore blockStore = BlockStoreImpl.forNode(dbFactory);
            blockStore.start();
            AddressStore addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
            addressStore.start();
            OrphanBlockStore orphanBlockStore = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND),
                    kernel);
            orphanBlockStore.start();
            chainStore = new ChainL1Store(dbFactory.getDB(DatabaseName.CHAIN_L1));
            chainStore.start();
            kernel.setBlockStore(blockStore);
            kernel.setAddressStore(addressStore);
            kernel.setOrphanBlockStore(orphanBlockStore);
            kernel.setChainL1Store(chainStore);

            // The consistency check runs inside this constructor; in repair mode it records rather
            // than throws, and the tool re-scans the stores for itself anyway.
            blockchain = new BlockchainImpl(kernel);
            if (reinit) {
                return ChainRepairTool.reinitMarker(kernel, blockchain, System.out::println).clean() ? 0 : 2;
            }
            ChainRepairTool.Outcome outcome = ChainRepairTool.repair(kernel, blockchain,
                    new ChainRepairTool.Options(dryRun, force), System.out::println);
            if (outcome.reason() != null) {
                System.out.println("--repairchain: " + outcome.reason());
            }
            return switch (outcome.status()) {
                case CLEAN, REPAIRED, PLANNED -> 0;
                case REFUSED -> 1;
                case UNREPAIRABLE -> 2;
            };
        } catch (Exception e) {
            // e, not e.getMessage(): a RocksDB failure may carry no message at all.
            System.out.println("--repairchain failed: " + e);
            return 3;
        } finally {
            if (blockchain != null) {
                // Also stops the cleaner the constructor started on a non-daemon thread; without
                // this the JVM would never exit.
                blockchain.stopCheckMain();
            }
            if (chainStore != null) {
                chainStore.stop();
            }
            dbFactory.close();
        }
    }

    /**
     * Copy directory recursively
     */
    public static void copyDir(String sourcePath, String newPath) {
        File start = new File(sourcePath);
        File end = new File(newPath);
        String[] filePath = start.list();  // Get all files and directories under this folder
        if (filePath == null) {
            throw new IllegalStateException("cannot copy " + sourcePath
                    + ": it is not an existing, readable directory");
        }
        if(!end.exists()) {
            end.mkdirs();
        }
        for(String temp:filePath) {
            // Check if each item is a file or directory
            if(new File(sourcePath+File.separator+temp).isDirectory()) {
                // For directory, recursively copy
                copyDir(sourcePath+File.separator+temp, newPath+File.separator+temp);
            }else {
                // For file, copy directly
                copyFile(sourcePath+File.separator+temp, newPath+File.separator+temp);
            }
        }
    }

    /**
     * Copy single file
     */
    public static void copyFile(String sourcePath, String newPath) {
        File start = new File(sourcePath);
        File end = new File(newPath);
        try(BufferedInputStream bis=new BufferedInputStream(new FileInputStream(start));
            BufferedOutputStream bos=new BufferedOutputStream(new FileOutputStream(end))) {
            int len;
            byte[] flush = new byte[1024];
            while((len=bis.read(flush)) != -1) {
                bos.write(flush, 0, len);
            }
            bos.flush();
        } catch(IOException e) {
            e.printStackTrace();
        }
    }
}