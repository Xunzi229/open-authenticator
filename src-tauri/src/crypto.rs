use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use argon2::{Algorithm, Argon2, Params, Version};
use rand::RngCore;
use thiserror::Error;
use zeroize::Zeroize;

const MAGIC: &[u8] = b"GAE1";
const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 12;
const TIME_COST: u32 = 3;
const MEMORY_KIB: u32 = 32 * 1024;
const PARALLELISM: u32 = 2;
const MIN_MEMORY_KIB: u32 = 8 * 1024;
const MAX_MEMORY_KIB: u32 = 256 * 1024;
const MAX_TIME_COST: u32 = 10;
const MAX_PARALLELISM: u32 = 8;

#[derive(Debug, Error)]
pub enum CryptoError {
    #[error("vault corrupted")]
    Corrupted,
    #[error("wrong password or vault corrupted")]
    Auth,
}

fn derive(
    password: &str,
    salt: &[u8],
    time: u32,
    memory: u32,
    para: u32,
) -> Result<[u8; 32], CryptoError> {
    if !(MIN_MEMORY_KIB..=MAX_MEMORY_KIB).contains(&memory)
        || !(1..=MAX_TIME_COST).contains(&time)
        || !(1..=MAX_PARALLELISM).contains(&para)
    {
        return Err(CryptoError::Corrupted);
    }
    let params = Params::new(memory, time, para, Some(32)).map_err(|_| CryptoError::Corrupted)?;
    let argon = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut key = [0u8; 32];
    argon
        .hash_password_into(password.as_bytes(), salt, &mut key)
        .map_err(|_| CryptoError::Corrupted)?;
    Ok(key)
}

pub fn encrypt(plain: &[u8], password: &str) -> Result<Vec<u8>, CryptoError> {
    let mut salt = [0u8; SALT_LEN];
    let mut nonce = [0u8; NONCE_LEN];
    rand::thread_rng().fill_bytes(&mut salt);
    rand::thread_rng().fill_bytes(&mut nonce);
    let mut key = derive(password, &salt, TIME_COST, MEMORY_KIB, PARALLELISM)?;
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| CryptoError::Corrupted)?;
    key.zeroize();
    let sealed = cipher
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: plain,
                aad: MAGIC,
            },
        )
        .map_err(|_| CryptoError::Corrupted)?;
    let mut out = Vec::with_capacity(4 + SALT_LEN + 6 + NONCE_LEN + sealed.len());
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&salt);
    out.extend_from_slice(&MEMORY_KIB.to_be_bytes());
    out.push(TIME_COST as u8);
    out.push(PARALLELISM as u8);
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&sealed);
    Ok(out)
}

pub fn decrypt(data: &[u8], password: &str) -> Result<Vec<u8>, CryptoError> {
    if data.len() < 4 + SALT_LEN + 6 + NONCE_LEN + 16 || &data[..4] != MAGIC {
        return Err(CryptoError::Corrupted);
    }
    let mut pos = 4;
    let salt = &data[pos..pos + SALT_LEN];
    pos += SALT_LEN;
    let memory = u32::from_be_bytes(data[pos..pos + 4].try_into().unwrap());
    let time = data[pos + 4] as u32;
    let para = data[pos + 5] as u32;
    pos += 6;
    let nonce = &data[pos..pos + NONCE_LEN];
    pos += NONCE_LEN;
    let mut key = derive(password, salt, time, memory, para)?;
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| CryptoError::Corrupted)?;
    key.zeroize();
    cipher
        .decrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: &data[pos..],
                aad: MAGIC,
            },
        )
        .map_err(|_| CryptoError::Auth)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_untrusted_expensive_kdf_parameters() {
        let mut blob = encrypt(b"payload", "correct horse").unwrap();
        blob[20..24].copy_from_slice(&u32::MAX.to_be_bytes());
        assert!(matches!(
            decrypt(&blob, "correct horse"),
            Err(CryptoError::Corrupted)
        ));
    }
}
