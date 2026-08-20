// SPDX-License-Identifier: MIT
pragma solidity 0.8.26;

/// XDAG bridge withdrawal entry (spec 2026-08-19 section 3.1). No admin, no upgrade, no selfdestruct.
/// msg.value stays in the contract forever: its balance is the cumulative burned wei (audit figure).
contract XdagBridge {
    event Withdrawal(bytes20 indexed nativeTarget, uint256 amount);

    function withdraw(bytes20 nativeTarget) external payable {
        require(msg.value > 0 && msg.value % 1e9 == 0, "bad amount");
        emit Withdrawal(nativeTarget, msg.value);
    }
}
