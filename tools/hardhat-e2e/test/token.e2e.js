const { expect } = require("chai");
const hre = require("hardhat");

// Run against a live node: npx hardhat test --network xdagDevnet
describe("DemoToken on XDAG devnet", function () {
  it("deploys, transfers, and emits Transfer", async function () {
    const [deployer] = await hre.ethers.getSigners();
    const Token = await hre.ethers.getContractFactory("DemoToken");
    const token = await Token.deploy(1_000_000n * 10n ** 18n);
    await token.waitForDeployment();

    const to = "0x00000000000000000000000000000000000000aa";
    await (await token.transfer(to, 1234n)).wait();
    expect(await token.balanceOf(to)).to.equal(1234n);

    const events = await token.queryFilter(token.filters.Transfer(), 0, "latest");
    expect(events.length).to.be.greaterThan(0);
  });
});
