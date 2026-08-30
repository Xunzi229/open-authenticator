use reqwest::blocking::{Client, Response};
use reqwest::header::{CONTENT_LENGTH, ETAG, IF_MATCH, IF_NONE_MATCH};
use reqwest::redirect::Policy;
use reqwest::StatusCode;
use sha2::{Digest, Sha256};
use std::io::Read;
use url::Url;

pub const MAX_REMOTE_BYTES: u64 = 16 * 1024 * 1024;

pub struct Download {
    pub bytes: Vec<u8>,
    pub etag: String,
}

fn fail(op: &str, status: StatusCode) -> String {
    if status == StatusCode::UNAUTHORIZED {
        format!("{op}认证失败，请检查用户名和应用密码")
    } else if status == StatusCode::PRECONDITION_FAILED {
        "远程保险库已被其他设备修改，请先拉取并确认内容".into()
    } else {
        format!("{op}失败 ({status})")
    }
}

fn target(base: &str, path: &str) -> Result<(Url, Url), String> {
    let mut base = base.trim().to_string();
    if base.is_empty() {
        return Err("请先填写 WebDAV 地址".into());
    }
    if !base.ends_with('/') {
        base.push('/');
    }
    let base = Url::parse(&base).map_err(|_| "WebDAV 地址无效")?;
    if !base.username().is_empty() || base.password().is_some() {
        return Err("请不要把用户名或密码写入 WebDAV 地址".into());
    }
    let local_http = base.scheme() == "http"
        && matches!(base.host_str(), Some("localhost" | "127.0.0.1" | "::1"));
    if base.scheme() != "https" && !local_http {
        return Err("WebDAV 必须使用 HTTPS；仅本机地址允许 HTTP".into());
    }
    let dest = base
        .join(path.trim_start_matches('/'))
        .map_err(|_| "WebDAV 路径无效")?;
    if dest.origin() != base.origin() {
        return Err("WebDAV 路径不能跳转到其他服务器".into());
    }
    Ok((base, dest))
}

fn client() -> Result<Client, String> {
    Client::builder()
        .timeout(std::time::Duration::from_secs(25))
        .redirect(Policy::none())
        .build()
        .map_err(|e| e.to_string())
}

fn auth(
    request: reqwest::blocking::RequestBuilder,
    user: &str,
    pass: &str,
) -> reqwest::blocking::RequestBuilder {
    if user.is_empty() {
        request
    } else {
        request.basic_auth(user, Some(pass))
    }
}

fn response_etag(resp: &Response) -> Option<String> {
    resp.headers()
        .get(ETAG)
        .and_then(|value| value.to_str().ok())
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .filter(|value| !value.starts_with("W/"))
        .map(str::to_string)
}

fn content_validator(bytes: &[u8]) -> String {
    format!("sha256:{}", hex::encode(Sha256::digest(bytes)))
}

fn read_download(mut resp: Response) -> Result<Download, String> {
    if resp
        .headers()
        .get(CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u64>().ok())
        .is_some_and(|size| size > MAX_REMOTE_BYTES)
    {
        return Err("远程保险库超过 16 MiB 限制".into());
    }
    let etag = response_etag(&resp);
    let mut bytes = Vec::new();
    resp.by_ref()
        .take(MAX_REMOTE_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.is_empty() {
        return Err("远程文件是空的".into());
    }
    if bytes.len() as u64 > MAX_REMOTE_BYTES {
        return Err("远程保险库超过 16 MiB 限制".into());
    }
    let etag = etag.unwrap_or_else(|| content_validator(&bytes));
    Ok(Download { bytes, etag })
}

fn get_optional(
    client: &Client,
    dest: &Url,
    user: &str,
    pass: &str,
) -> Result<Option<Download>, String> {
    let resp = auth(client.get(dest.clone()), user, pass)
        .send()
        .map_err(|e| e.to_string())?;
    if resp.status() == StatusCode::NOT_FOUND {
        return Ok(None);
    }
    if resp.status() != StatusCode::OK {
        return Err(fail("WebDAV 下载", resp.status()));
    }
    read_download(resp).map(Some)
}

fn ensure_collections(
    client: &Client,
    base: &Url,
    path: &str,
    user: &str,
    pass: &str,
) -> Result<(), String> {
    let parts: Vec<&str> = path
        .trim_matches('/')
        .split('/')
        .filter(|part| !part.is_empty())
        .collect();
    if parts.len() < 2 {
        return Ok(());
    }
    let mut current = base.clone();
    for part in &parts[..parts.len() - 1] {
        current = current
            .join(&format!("{part}/"))
            .map_err(|_| "WebDAV 路径无效")?;
        let method = reqwest::Method::from_bytes(b"MKCOL").map_err(|e| e.to_string())?;
        let resp = auth(client.request(method, current.clone()), user, pass)
            .send()
            .map_err(|e| e.to_string())?;
        if !matches!(resp.status().as_u16(), 200 | 201 | 204 | 405) {
            return Err(fail("WebDAV 创建目录", resp.status()));
        }
    }
    Ok(())
}

pub fn put(
    base: &str,
    path: &str,
    user: &str,
    pass: &str,
    data: &[u8],
    expected_etag: Option<&str>,
) -> Result<String, String> {
    if data.len() as u64 > MAX_REMOTE_BYTES {
        return Err("保险库文件过大".into());
    }
    let (base, dest) = target(base, path)?;
    let client = client()?;
    ensure_collections(&client, &base, path, user, pass)?;
    let mut request = client
        .put(dest.clone())
        .header("Content-Type", "application/octet-stream");
    match expected_etag {
        Some(expected) if expected.starts_with("sha256:") => {
            let current = get_optional(&client, &dest, user, pass)?
                .ok_or_else(|| "远程保险库已被删除，请先拉取确认状态".to_string())?;
            if content_validator(&current.bytes) != expected {
                return Err("远程保险库已被其他设备修改，请先拉取并确认内容".into());
            }
            if !current.etag.starts_with("sha256:") {
                request = request.header(IF_MATCH, current.etag);
            }
        }
        Some(expected) => {
            request = request.header(IF_MATCH, expected);
        }
        None => {
            if let Some(current) = get_optional(&client, &dest, user, pass)? {
                if current.bytes == data {
                    return Ok(current.etag);
                }
                return Err("远程保险库已存在，请先拉取并确认内容".into());
            }
            request = request.header(IF_NONE_MATCH, "*");
        }
    }
    let request = auth(request.body(data.to_vec()), user, pass);
    let resp = request.send().map_err(|e| e.to_string())?;
    if !matches!(resp.status().as_u16(), 200 | 201 | 204 | 207) {
        return Err(fail("WebDAV 上传", resp.status()));
    }
    if let Some(etag) = response_etag(&resp) {
        return Ok(etag);
    }
    let uploaded = get_optional(&client, &dest, user, pass)?
        .ok_or_else(|| "WebDAV 上传后未找到远程文件".to_string())?;
    if uploaded.bytes != data {
        return Err("WebDAV 上传后校验失败，远程内容与本地不一致".into());
    }
    Ok(uploaded.etag)
}

pub fn get(base: &str, path: &str, user: &str, pass: &str) -> Result<Download, String> {
    let (_, dest) = target(base, path)?;
    let client = client()?;
    get_optional(&client, &dest, user, pass)?
        .ok_or_else(|| "WebDAV 远程保险库不存在".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_plaintext_remote_webdav() {
        assert!(target("http://dav.example.com", "/vault.enc").is_err());
        assert!(target("http://127.0.0.1:8080", "/vault.enc").is_ok());
    }

    #[test]
    fn rejects_credentials_embedded_in_url() {
        assert!(target("https://user:pass@dav.example.com", "/vault.enc").is_err());
    }

    #[test]
    fn content_validator_is_stable_and_namespaced() {
        let first = content_validator(b"vault");
        assert_eq!(first, content_validator(b"vault"));
        assert_ne!(first, content_validator(b"other"));
        assert!(first.starts_with("sha256:"));
        assert_eq!(first.len(), "sha256:".len() + 64);
    }
}
