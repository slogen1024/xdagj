fn main() {
    // wasm-ld still emits an unused funcref table. strip_table.py drops that
    // section after this link; ModuleValidator rejects the 0x70 encoding.
    // rustc's default wasm memory has no maximum. ModuleValidator rejects that.
    // 64 pages is GasSchedule.MAX_MEMORY_PAGES. The flag is bytes, not pages.
    println!("cargo:rustc-link-arg=--max-memory=4194304");
    println!("cargo:rustc-link-arg=-z");
    println!("cargo:rustc-link-arg=stack-size=4096");
}
