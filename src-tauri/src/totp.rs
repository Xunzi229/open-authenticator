use data_encoding::BASE32_NOPAD;
use hmac::{Hmac, Mac};
use sha1::Sha1;
use sha2::{Sha256, Sha512};
use std::time::{SystemTime, UNIX_EPOCH};

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs()
}

fn b32(secret: &str) -> Vec<u8> {
    let cleaned: String = secret
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| c.to_ascii_uppercase())
        .collect();
    BASE32_NOPAD.decode(cleaned.as_bytes()).unwrap_or_default()
}

fn hmac_sha1(key: &[u8], msg: &[u8]) -> Vec<u8> {
    let mut mac = Hmac::<Sha1>::new_from_slice(key).expect("hmac");
    mac.update(msg);
    mac.finalize().into_bytes().to_vec()
}
fn hmac_sha256(key: &[u8], msg: &[u8]) -> Vec<u8> {
    let mut mac = Hmac::<Sha256>::new_from_slice(key).expect("hmac");
    mac.update(msg);
    mac.finalize().into_bytes().to_vec()
}
fn hmac_sha512(key: &[u8], msg: &[u8]) -> Vec<u8> {
    let mut mac = Hmac::<Sha512>::new_from_slice(key).expect("hmac");
    mac.update(msg);
    mac.finalize().into_bytes().to_vec()
}

pub fn totp(secret: &str, digits: u32, algo: &str, period: u64) -> String {
    let digits = if digits == 0 { 6 } else { digits };
    let period = if period == 0 { 30 } else { period };
    let counter = now_secs() / period;
    let mut msg = [0u8; 8];
    msg.copy_from_slice(&counter.to_be_bytes());
    let key = b32(secret);
    let algo = algo.to_ascii_uppercase().replace('-', "");
    let hash = match algo.as_str() {
        "SHA256" => hmac_sha256(&key, &msg),
        "SHA512" => hmac_sha512(&key, &msg),
        _ => hmac_sha1(&key, &msg),
    };
    let offset = (hash[hash.len() - 1] & 0x0f) as usize;
    let bin = ((hash[offset] as u32 & 0x7f) << 24)
        | ((hash[offset + 1] as u32) << 16)
        | ((hash[offset + 2] as u32) << 8)
        | (hash[offset + 3] as u32);
    let modn = 10u32.pow(digits);
    format!("{:0width$}", bin % modn, width = digits as usize)
}

pub fn remain(period: u64) -> u64 {
    let period = if period == 0 { 30 } else { period };
    period - (now_secs() % period)
}
