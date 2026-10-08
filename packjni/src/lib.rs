//! JNI bridge to Google's `pack` (APK builder and signer).
//!
//! Kotlin side: `com.seyedjafari.analogplus.packjni.PackNative`. Resources arrive as parallel arrays
//! (types, names, contents) so no JNI object fields are touched. Every failure, including a panic,
//! is thrown as `PackException` carrying the Rust error text; nothing may abort the process.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JByteArray, JObject, JObjectArray, JString};
use jni::sys::{jbyteArray, jstring};
use jni::JNIEnv;
use pack_api::{compile_and_sign_apk, FileResource, Keys, Package};
use rsa::pkcs8::{EncodePrivateKey, LineEnding};

const EXCEPTION_CLASS: &str = "com/seyedjafari/analogplus/packjni/PackException";

/// Distinguishes "throw this" from "a Java exception is already pending, just return".
enum Failure {
    Message(String),
    JavaPending,
}

impl From<jni::errors::Error> for Failure {
    fn from(e: jni::errors::Error) -> Self {
        match e {
            jni::errors::Error::JavaException => Failure::JavaPending,
            other => Failure::Message(format!("JNI error: {other}")),
        }
    }
}

fn msg<E: std::fmt::Display>(context: &str, e: E) -> Failure {
    Failure::Message(format!("{context}: {e}"))
}

fn panic_text(p: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = p.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = p.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic".to_string()
    }
}

/// Runs `f`, converting an error or panic into a thrown `PackException` and a null return.
fn guarded<T>(
    env: &mut JNIEnv,
    f: impl FnOnce(&mut JNIEnv) -> Result<T, Failure>,
) -> Option<T> {
    let outcome = catch_unwind(AssertUnwindSafe(|| f(env)));
    let failure = match outcome {
        Ok(Ok(v)) => return Some(v),
        Ok(Err(Failure::JavaPending)) => return None,
        Ok(Err(Failure::Message(m))) => m,
        Err(p) => format!("pack native panic: {}", panic_text(p)),
    };
    if !env.exception_check().unwrap_or(false) {
        let _ = env.throw_new(EXCEPTION_CLASS, failure);
    }
    None
}

fn string_array(env: &mut JNIEnv, arr: &JObjectArray) -> Result<Vec<String>, Failure> {
    let len = env.get_array_length(arr)?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let obj = env.get_object_array_element(arr, i)?;
        let s: String = env.get_string(&JString::from(obj))?.into();
        out.push(s);
    }
    Ok(out)
}

fn build(
    env: &mut JNIEnv,
    manifest: &JString,
    types: &JObjectArray,
    names: &JObjectArray,
    contents: &JObjectArray,
    pem: &JString,
) -> Result<Vec<u8>, Failure> {
    let manifest: String = env.get_string(manifest)?.into();
    let pem: String = env.get_string(pem)?.into();
    let types = string_array(env, types)?;
    let names = string_array(env, names)?;
    let n = env.get_array_length(contents)? as usize;
    if types.len() != n || names.len() != n {
        return Err(Failure::Message(format!(
            "resource arrays differ in length: {} types, {} names, {} contents",
            types.len(),
            names.len(),
            n
        )));
    }
    let mut resources = Vec::with_capacity(n);
    for i in 0..n {
        let obj = env.get_object_array_element(contents, i as i32)?;
        let bytes = env.convert_byte_array(JByteArray::from(obj))?;
        resources.push(FileResource::new(types[i].clone(), names[i].clone(), bytes));
    }
    let keys = Keys::from_combined_pem_string(&pem).map_err(|e| msg("signing keys", e))?;
    let package = Package { android_manifest: manifest.into_bytes(), resources };
    compile_and_sign_apk(&package, &keys).map_err(|e| msg("pack", e))
}

fn generate_pem() -> Result<String, Failure> {
    let keys = Keys::generate_random_testing_keys().map_err(|e| msg("key generation", e))?;
    let cert = pem::encode(&pem::Pem::new("CERTIFICATE", keys.certificate));
    let key = keys
        .private_key
        .to_pkcs8_pem(LineEnding::LF)
        .map_err(|e| msg("encoding private key", e))?;
    Ok(format!("{cert}{}", key.as_str()))
}

#[no_mangle]
pub extern "system" fn Java_com_seyedjafari_analogplus_packjni_PackNative_nativeBuild<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    manifest: JString<'l>,
    types: JObjectArray<'l>,
    names: JObjectArray<'l>,
    contents: JObjectArray<'l>,
    pem: JString<'l>,
) -> jbyteArray {
    let bytes = guarded(&mut env, |env| build(env, &manifest, &types, &names, &contents, &pem));
    match bytes {
        Some(b) => match env.byte_array_from_slice(&b) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_seyedjafari_analogplus_packjni_PackNative_nativeGenerateKeysPem<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
) -> jstring {
    let pem = guarded(&mut env, |_| generate_pem());
    match pem {
        Some(p) => match env.new_string(p) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}
