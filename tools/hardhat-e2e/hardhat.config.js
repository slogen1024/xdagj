require("@nomicfoundation/hardhat-toolbox");

// XDAG devnet supports both legacy and EIP-1559 (type-2) txs: baseFee is always 0, the effective
// price is min(maxPriorityFeePerGas, maxFeePerGas), and eth_feeHistory / eth_maxPriorityFeePerGas
// are served. The gasPrice below is now optional — kept as the simplest deterministic choice
// (forces legacy txs); removing it switches Hardhat/ethers to the type-2 fee flow, which also works.
module.exports = {
  solidity: "0.8.20",
  networks: {
    xdagDevnet: {
      url: "http://127.0.0.1:10001",
      chainId: 51966, // 0xCAFE
      // Private key 1 -> address 0x7e5f4552091a69125d5dfcb7b8c2659029395bdf, pre-funded on devnet.
      accounts: ["0x0000000000000000000000000000000000000000000000000000000000000001"],
      gasPrice: 1,
    },
  },
};
