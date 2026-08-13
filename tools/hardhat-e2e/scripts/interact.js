const hre = require("hardhat");

// Usage: TOKEN=0x... npx hardhat run scripts/interact.js --network xdagDevnet
async function main() {
  const addr = process.env.TOKEN;
  if (!addr) throw new Error("set TOKEN=<deployed address>");
  const [deployer] = await hre.ethers.getSigners();
  const token = await hre.ethers.getContractAt("DemoToken", addr);

  console.log("balanceOf(deployer)", (await token.balanceOf(deployer.address)).toString());
  const to = "0x00000000000000000000000000000000000000aa";
  const tx = await token.transfer(to, 1000n);
  await tx.wait();
  console.log("transferred; balanceOf(to)", (await token.balanceOf(to)).toString());

  const events = await token.queryFilter(token.filters.Transfer(), 0, "latest");
  console.log("Transfer events:", events.length);
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
