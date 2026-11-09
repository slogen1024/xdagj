//! `#[xdag::contract]` turns `#[xdag::method("sig")]` functions into `call`.
//! A method marked `reentrant` is listed by `reentrant_selectors`. This does not emit wasm.

use proc_macro::TokenStream;
use quote::quote;
use sha2::{Digest, Sha256};
use syn::{parse_macro_input, ImplItem, ItemImpl, Token};

#[proc_macro_attribute]
pub fn contract(_attr: TokenStream, item: TokenStream) -> TokenStream {
    let mut imp = parse_macro_input!(item as ItemImpl);
    let mut arms = Vec::new();
    let mut reentrant = Vec::new();
    let mut error = None;
    for item in &mut imp.items {
        let ImplItem::Fn(func) = item else {
            continue;
        };
        let mut keep = Vec::new();
        for attr in func.attrs.drain(..) {
            if !is_method(&attr) {
                keep.push(attr);
                continue;
            }
            match method_args(&attr) {
                Ok((sig, re)) => {
                    let sel = selector(&sig);
                    let ident = func.sig.ident.clone();
                    arms.push(quote! {
                        if host.selector == #sel {
                            return Self::#ident(host);
                        }
                    });
                    if re {
                        reentrant.push(sel);
                    }
                }
                Err(err) => error = Some(err),
            }
        }
        func.attrs = keep;
    }
    if let Some(err) = error {
        return err.to_compile_error().into();
    }
    let re_elems = reentrant.iter().map(|sel| quote!(#sel));
    imp.items.push(ImplItem::Verbatim(
        quote! {
            pub fn call(host: &mut crate::host::Host) -> bool {
                #(#arms)*
                false
            }

            pub fn reentrant_selectors() -> &'static [i32] {
                &[#(#re_elems),*]
            }
        }
        .into(),
    ));
    quote!(#imp).into()
}

#[proc_macro_attribute]
pub fn method(_attr: TokenStream, item: TokenStream) -> TokenStream {
    item
}

fn is_method(attr: &syn::Attribute) -> bool {
    attr.path()
        .segments
        .last()
        .map(|seg| seg.ident == "method")
        .unwrap_or(false)
}

fn method_args(attr: &syn::Attribute) -> syn::Result<(String, bool)> {
    let parser = syn::punctuated::Punctuated::<syn::Expr, Token![,]>::parse_terminated;
    let args = attr.parse_args_with(parser)?;
    let mut iter = args.into_iter();
    let Some(first) = iter.next() else {
        return Err(syn::Error::new_spanned(attr, "#[xdag::method(\"signature\")]"));
    };
    let syn::Expr::Lit(syn::ExprLit {
        lit: syn::Lit::Str(sig),
        ..
    }) = first
    else {
        return Err(syn::Error::new_spanned(attr, "#[xdag::method(\"signature\")]"));
    };
    let mut reentrant = false;
    if let Some(rest) = iter.next() {
        let syn::Expr::Path(path) = &rest else {
            return Err(syn::Error::new_spanned(rest, "expected reentrant"));
        };
        if !path.path.is_ident("reentrant") {
            return Err(syn::Error::new_spanned(rest, "expected reentrant"));
        }
        reentrant = true;
    }
    if iter.next().is_some() {
        return Err(syn::Error::new_spanned(attr, "too many method arguments"));
    }
    Ok((sig.value(), reentrant))
}

fn selector(signature: &str) -> i32 {
    let hash = Sha256::digest(signature.as_bytes());
    i32::from_be_bytes([hash[0], hash[1], hash[2], hash[3]])
}
