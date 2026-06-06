# Solidity test fixtures (XDAG EVM, Sub-project A)

Each `*.bin` is the **single-line hex creation (init) bytecode** — no `0x` prefix, no trailing
newline — loaded at test time by `EvmTestBase.readBin(...)`. The Maven build does **not** run `solc`;
these are generated offline once and committed, so the build stays hermetic.

> Status: the `.sol` sources are committed. The `.bin` files are produced at the verification gate
> (a build environment with `solc`/`docker`), per the no-build session. Until then the USDT tests
> cannot run.

## `usdt.bin` — real Tether (Ethereum mainnet USDT)

`usdt.sol` is the verbatim Etherscan-verified `TetherToken.sol`
(`0xdac17f958d2ee523a2206206994597c13d831ec7`, pragma `^0.4.17`, decimals = 6, non-standard
no-return-value `transfer`/`approve`, plus `issue`/`redeem`/`pause`/blacklist/fee). Vendored from
`https://github.com/tethercoin/USDT` (Apache-2.0 — original header preserved).

```bash
cd src/test/resources/solidity
docker run --rm -v "$PWD":/src ethereum/solc:0.4.18 \
  --bin --optimize -o /src/out --overwrite /src/usdt.sol
tr -d '\n' < out/TetherToken.bin > usdt.bin
```

Alternative (no solc): fetch the deployed contract-creation transaction input of the mainnet
address from Etherscan, strip the trailing ABI-encoded constructor args, and save the remaining hex
as `usdt.bin` (the test appends its own constructor args at deploy time).

## `usdt_oz.bin` — clean OpenZeppelin-style ERC-20 (standard bool returns, 6 decimals)

```bash
cd src/test/resources/solidity
docker run --rm -v "$PWD":/src ethereum/solc:0.8.26 \
  --bin --optimize --evm-version shanghai -o /src/out --overwrite /src/usdt_oz.sol
tr -d '\n' < out/UsdtOz.bin > usdt_oz.bin
```

> Always target `--evm-version shanghai` for `usdt_oz.sol` so the bytecode matches the EVM the
> executor runs (Shanghai), and never emits Cancun-only opcodes (MCOPY/TLOAD).
