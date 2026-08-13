const hre = require("hardhat");

async function main() {
  const [deployer] = await hre.ethers.getSigners();
  console.log("Deploying DemoToken from", deployer.address);
  const Token = await hre.ethers.getContractFactory("DemoToken");
  const token = await Token.deploy(1_000_000n * 10n ** 18n);
  await token.waitForDeployment();
  console.log("DemoToken deployed at", await token.getAddress());
  console.log("Deploy tx", token.deploymentTransaction().hash);
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
