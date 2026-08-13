require("@nomicfoundation/hardhat-toolbox");

// XDAG devnet is legacy-gas only (no EIP-1559): set gasPrice explicitly so Hardhat/ethers do NOT
// call eth_feeHistory / eth_maxPriorityFeePerGas (which the node does not implement).
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
