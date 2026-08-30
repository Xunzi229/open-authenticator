use reqwest::blocking::Client;
use reqwest::StatusCode;
use url::Url;

fn target(base: &str, path: &str) -> Result<String, String> {
    let mut base = base.trim().to_string();
    if !base.ends_with('/') {
        base.push('/');
    }
    let u = Url::parse(&base).map_err(|_| "WebDAV 地址无效")?;
    if u.scheme() != "http" && u.scheme() != "https" {
        return Err("WebDAV 地址无效".into());
    }
    Ok(u.join(path.trim_start_matches('/')).map_err(|_| "WebDAV 路径无效")?.to_string())
}

fn client() -> Result<Client, String> {
    Client::builder()
        .timeout(std::time::Duration::from_secs(25))
        .build()
        .map_err(|e| e.to_string())
}

pub fn put(base: &str, path: &str, user: &str, pass: &str, data: &[u8]) -> Result<(), String> {
    let dest = target(base, path)?;
    let c = client()?;
    let mut req = c.put(&dest).header("Content-Type", "application/octet-stream").body(data.to_vec());
    if !user.is_empty() {
        req = req.basic_auth(user, Some(pass));
    }
    let resp = req.send().map_err(|e| e.to_string())?;
    if matches!(resp.status(), StatusCode::NOT_FOUND | StatusCode::CONFLICT) {
        let parent = dest.rsplit_once('/').map(|(a, _)| format!("{a}/")).unwrap_or(dest.clone());
        let mut mk = c.request(reqwest::Method::from_bytes(b"MKCOL").unwrap(), &parent);
        if !user.is_empty() {
            mk = mk.basic_auth(user, Some(pass));
        }
        let _ = mk.send();
        let mut req2 = c.put(&dest).header("Content-Type", "application/octet-stream").body(data.to_vec());
        if !user.is_empty() {
            req2 = req2.basic_auth(user, Some(pass));
        }
        let resp2 = req2.send().map_err(|e| e.to_string())?;
        if !matches!(resp2.status().as_u16(), 200 | 201 | 204 | 207) {
            return Err(format!("WebDAV 上传失败 ({})", resp2.status()));
        }
        return Ok(());
    }
    if !matches!(resp.status().as_u16(), 200 | 201 | 204 | 207) {
        return Err(format!("WebDAV 上传失败 ({})", resp.status()));
    }
    Ok(())
}

pub fn get(base: &str, path: &str, user: &str, pass: &str) -> Result<Vec<u8>, String> {
    let dest = target(base, path)?;
    let c = client()?;
    let mut req = c.get(&dest);
    if !user.is_empty() {
        req = req.basic_auth(user, Some(pass));
    }
    let resp = req.send().map_err(|e| e.to_string())?;
    if resp.status() != StatusCode::OK {
        return Err(format!("WebDAV 下载失败 ({})", resp.status()));
    }
    let b = resp.bytes().map_err(|e| e.to_string())?;
    if b.is_empty() {
        return Err("远程文件是空的".into());
    }
    Ok(b.to_vec())
}
