use data_encoding::BASE32_NOPAD;
use hmac::{Hmac, Mac};
use sha1::Sha1;
use sha2::{Sha256, Sha512};
use std::time::{SystemTime, UNIX_EPOCH};

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs()
}

pub fn normalize_secret(secret: &str) -> Result<String, String> {
    let mut cleaned: String = secret
        .chars()
        .filter(|c| !c.is_ascii_whitespace() && *c != '-')
        .map(|c| c.to_ascii_uppercase())
        .collect();
    while cleaned.ends_with('=') {
        cleaned.pop();
    }
    if cleaned.is_empty()
        || !cleaned
            .chars()
            .all(|c| c.is_ascii_uppercase() || ('2'..='7').contains(&c))
        || BASE32_NOPAD.decode(cleaned.as_bytes()).map_or(true, |v| v.is_empty())
    {
        return Err("密钥不是有效的 Base32".into());
    }
    Ok(cleaned)
}

pub fn validate_parameters(digits: u32, algo: &str, period: u64) -> Result<String, String> {
    if !matches!(digits, 6 | 8) {
        return Err("验证码位数只能是 6 或 8".into());
    }
    if !(1..=86_400).contains(&period) {
        return Err("验证码周期必须在 1 到 86400 秒之间".into());
    }
    let algorithm = algo.to_ascii_uppercase().replace('-', "");
    if !matches!(algorithm.as_str(), "SHA1" | "SHA256" | "SHA512") {
        return Err("不支持的验证码算法".into());
    }
    Ok(algorithm)
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

pub fn totp(secret: &str, digits: u32, algo: &str, period: u64) -> Result<String, String> {
    totp_at(secret, digits, algo, period, now_secs())
}

fn totp_at(
    secret: &str,
    digits: u32,
    algo: &str,
    period: u64,
    timestamp: u64,
) -> Result<String, String> {
    let secret = normalize_secret(secret)?;
    let algo = validate_parameters(digits, algo, period)?;
    let counter = timestamp / period;
    let mut msg = [0u8; 8];
    msg.copy_from_slice(&counter.to_be_bytes());
    let key = BASE32_NOPAD
        .decode(secret.as_bytes())
        .map_err(|_| "密钥不是有效的 Base32".to_string())?;
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
    Ok(format!("{:0width$}", bin % modn, width = digits as usize))
}

pub fn remain(period: u64) -> u64 {
    let period = if period == 0 { 30 } else { period };
    period - (now_secs() % period)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_invalid_secret_and_parameters() {
        assert!(normalize_secret("not base32 0189").is_err());
        assert!(validate_parameters(7, "SHA1", 30).is_err());
        assert!(validate_parameters(6, "MD5", 30).is_err());
        assert!(validate_parameters(6, "SHA1", 0).is_err());
    }

    #[test]
    fn accepts_grouped_base32() {
        assert_eq!(
            normalize_secret("jbsw-y3dp ehpk3pxp==").unwrap(),
            "JBSWY3DPEHPK3PXP"
        );
    }

    #[test]
    fn matches_rfc_6238_vectors() {
        let sha1 = BASE32_NOPAD.encode(b"12345678901234567890");
        let sha256 = BASE32_NOPAD.encode(b"12345678901234567890123456789012");
        let sha512 = BASE32_NOPAD.encode(
            b"1234567890123456789012345678901234567890123456789012345678901234",
        );
        assert_eq!(totp_at(&sha1, 8, "SHA1", 30, 59).unwrap(), "94287082");
        assert_eq!(totp_at(&sha256, 8, "SHA256", 30, 59).unwrap(), "46119246");
        assert_eq!(totp_at(&sha512, 8, "SHA512", 30, 59).unwrap(), "90693936");
    }
}
