use base64::Engine;
use data_encoding::BASE32_NOPAD;
use image::imageops::{self, FilterType};
use image::GrayImage;
use serde::Serialize;
use url::Url;

#[derive(Clone, Debug, Serialize)]
pub struct QrAccount {
    pub issuer: String,
    pub name: String,
    pub secret: String,
    pub algorithm: String,
    pub digits: u32,
    pub period: u64,
}

pub fn parse_uri(uri: &str) -> Result<Vec<QrAccount>, String> {
    let uri = uri.trim();
    if uri.starts_with("otpauth-migration://") {
        parse_migration(uri)
    } else if uri.starts_with("otpauth://") {
        parse_otpauth(uri)
    } else {
        Err("不是验证器二维码".into())
    }
}

fn parse_otpauth(uri: &str) -> Result<Vec<QrAccount>, String> {
    let u = Url::parse(uri).map_err(|_| "二维码链接无效")?;
    let secret = u
        .query_pairs()
        .find(|(k, _)| k == "secret")
        .map(|(_, v)| v.replace(' ', ""))
        .unwrap_or_default();
    if secret.is_empty() {
        return Err("QR 里没有 secret".into());
    }
    let mut issuer = u
        .query_pairs()
        .find(|(k, _)| k == "issuer")
        .map(|(_, v)| v.to_string())
        .unwrap_or_default();
    let mut name = percent_encoding::percent_decode_str(u.path().trim_start_matches('/'))
        .decode_utf8_lossy()
        .into_owned();
    if let Some(i) = name.find(':') {
        if issuer.is_empty() {
            issuer = name[..i].to_string();
        }
        name = name[i + 1..].to_string();
    }
    let mut algo = u
        .query_pairs()
        .find(|(k, _)| k == "algorithm")
        .map(|(_, v)| v.to_ascii_uppercase().replace('-', ""))
        .unwrap_or_else(|| "SHA1".into());
    if algo != "SHA1" && algo != "SHA256" && algo != "SHA512" {
        algo = "SHA1".into();
    }
    let digits = u
        .query_pairs()
        .find(|(k, _)| k == "digits")
        .and_then(|(_, v)| v.parse().ok())
        .unwrap_or(6);
    let period = u
        .query_pairs()
        .find(|(k, _)| k == "period")
        .and_then(|(_, v)| v.parse().ok())
        .unwrap_or(30);
    Ok(vec![QrAccount {
        issuer,
        name,
        secret,
        algorithm: algo,
        digits,
        period,
    }])
}

fn parse_migration(uri: &str) -> Result<Vec<QrAccount>, String> {
    let u = Url::parse(uri).map_err(|_| "二维码链接无效")?;
    let mut raw = u
        .query_pairs()
        .find(|(k, _)| k == "data")
        .map(|(_, v)| v.replace(' ', "+"))
        .unwrap_or_default();
    let pad = (4 - raw.len() % 4) % 4;
    raw.push_str(&"=".repeat(pad));
    let decoded = base64::engine::general_purpose::STANDARD
        .decode(&raw)
        .or_else(|_| base64::engine::general_purpose::URL_SAFE.decode(&raw))
        .map_err(|_| "migration 数据无法解码")?;
    let mut accounts = Vec::new();
    let mut pos = 0usize;
    while pos < decoded.len() {
        let Some((tag, p)) = read_varint(&decoded, pos) else {
            break;
        };
        pos = p;
        let field = tag >> 3;
        let wire = tag & 7;
        if wire == 0 {
            let Some((_, p)) = read_varint(&decoded, pos) else {
                break;
            };
            pos = p;
        } else if wire == 2 {
            let Some((len, p)) = read_varint(&decoded, pos) else {
                break;
            };
            pos = p;
            if pos + len > decoded.len() {
                break;
            }
            let value = &decoded[pos..pos + len];
            pos += len;
            if field == 1 {
                if let Some(a) = parse_otp(value) {
                    accounts.push(a);
                }
            }
        } else if wire == 1 {
            pos += 8;
        } else if wire == 5 {
            pos += 4;
        } else {
            break;
        }
    }
    if accounts.is_empty() {
        return Err("二维码里没有账号".into());
    }
    Ok(accounts)
}

fn parse_otp(data: &[u8]) -> Option<QrAccount> {
    let mut pos = 0usize;
    let mut secret = Vec::new();
    let mut name = String::new();
    let mut issuer = String::new();
    let mut algorithm = 1u32;
    let mut digits = 1u32;
    while pos < data.len() {
        let Some((tag, p)) = read_varint(data, pos) else {
            break;
        };
        pos = p;
        let field = tag >> 3;
        let wire = tag & 7;
        if wire == 0 {
            let Some((v, p)) = read_varint(data, pos) else {
                break;
            };
            pos = p;
            if field == 4 {
                algorithm = v as u32;
            }
            if field == 5 {
                digits = v as u32;
            }
        } else if wire == 2 {
            let Some((len, p)) = read_varint(data, pos) else {
                break;
            };
            pos = p;
            if pos + len > data.len() {
                break;
            }
            let value = &data[pos..pos + len];
            pos += len;
            match field {
                1 => secret = value.to_vec(),
                2 => name = String::from_utf8_lossy(value).into_owned(),
                3 => issuer = String::from_utf8_lossy(value).into_owned(),
                _ => {}
            }
        } else if wire == 1 {
            pos += 8;
        } else if wire == 5 {
            pos += 4;
        } else {
            break;
        }
    }
    if secret.is_empty() {
        return None;
    }
    // Google proto: 1=SHA1 2=SHA256 3=SHA512；DigitCount 1=6 2=8，也兼容直接写 6/8
    let algo = match algorithm {
        2 => "SHA256",
        3 => "SHA512",
        _ => "SHA1",
    };
    let d = if digits == 2 || digits == 8 { 8 } else { 6 };
    Some(QrAccount {
        issuer,
        name,
        secret: BASE32_NOPAD.encode(&secret),
        algorithm: algo.into(),
        digits: d,
        period: 30,
    })
}

fn read_varint(data: &[u8], mut pos: usize) -> Option<(usize, usize)> {
    let mut result = 0usize;
    let mut shift = 0;
    while pos < data.len() {
        let b = data[pos];
        pos += 1;
        result |= ((b & 0x7f) as usize) << shift;
        if b & 0x80 == 0 {
            return Some((result, pos));
        }
        shift += 7;
        if shift > 63 {
            return None;
        }
    }
    None
}

fn accounts_from_luma(img: GrayImage) -> Result<Vec<QrAccount>, String> {
    let mut prepared = rqrr::PreparedImage::prepare(img);
    let grids = prepared.detect_grids();
    if grids.is_empty() {
        return Err("图里没有识别到二维码".into());
    }
    let mut accounts = Vec::new();
    let mut last = "二维码无法解码".to_string();
    for grid in grids {
        match grid.decode() {
            Ok((_, content)) => match parse_uri(&content) {
                Ok(a) => accounts.extend(a),
                Err(e) => last = e,
            },
            Err(_) => last = "二维码无法解码".into(),
        }
    }
    if accounts.is_empty() {
        Err(last)
    } else {
        Ok(accounts)
    }
}

fn variants(src: &GrayImage) -> Vec<GrayImage> {
    let (w, h) = src.dimensions();
    let mut out = Vec::new();
    out.push(src.clone());
    let mut inv = src.clone();
    imageops::invert(&mut inv);
    out.push(inv);
    if w.max(h) > 1400 {
        let scale = 1200.0 / w.max(h) as f32;
        out.push(imageops::resize(
            src,
            ((w as f32) * scale).max(1.0) as u32,
            ((h as f32) * scale).max(1.0) as u32,
            FilterType::Triangle,
        ));
    }
    if w.min(h) < 700 {
        out.push(imageops::resize(src, w * 2, h * 2, FilterType::Triangle));
    }
    out
}

pub fn decode_image(bytes: &[u8]) -> Result<Vec<QrAccount>, String> {
    let img = image::load_from_memory(bytes).map_err(|_| "无法读取图片")?;
    let luma = img.to_luma8();
    let mut last = "图里没有识别到二维码".to_string();
    for v in variants(&luma) {
        match accounts_from_luma(v) {
            Ok(accs) if !accs.is_empty() => return Ok(accs),
            Ok(_) => {}
            Err(e) => last = e,
        }
    }
    Err(last)
}

pub fn otpauth_uri(a: &QrAccount) -> String {
    let label = if a.issuer.is_empty() {
        a.name.clone()
    } else if a.name.is_empty() {
        a.issuer.clone()
    } else {
        format!("{}:{}", a.issuer, a.name)
    };
    let mut q = format!(
        "secret={}&algorithm={}&digits={}&period={}",
        a.secret.replace(' ', ""),
        a.algorithm,
        a.digits,
        a.period
    );
    if !a.issuer.is_empty() {
        q.push_str("&issuer=");
        q.push_str(&enc(&a.issuer));
    }
    format!("otpauth://totp/{}?{}", enc(&label), q)
}

fn enc(s: &str) -> String {
    percent_encoding::utf8_percent_encode(s, percent_encoding::NON_ALPHANUMERIC).to_string()
}

fn secret_bytes(secret: &str) -> Result<Vec<u8>, String> {
    let cleaned: String = secret
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| c.to_ascii_uppercase())
        .collect();
    if cleaned.is_empty() {
        return Err("缺少密钥".into());
    }
    if let Ok(b) = BASE32_NOPAD.decode(cleaned.as_bytes()) {
        if !b.is_empty() {
            return Ok(b);
        }
    }
    let mut padded = cleaned;
    while padded.len() % 8 != 0 {
        padded.push('=');
    }
    data_encoding::BASE32
        .decode(padded.as_bytes())
        .map_err(|_| "密钥无效".into())
}

fn write_varint(out: &mut Vec<u8>, mut v: u64) {
    loop {
        let mut b = (v & 0x7f) as u8;
        v >>= 7;
        if v != 0 {
            b |= 0x80;
        }
        out.push(b);
        if v == 0 {
            break;
        }
    }
}

fn write_len(out: &mut Vec<u8>, field: u32, data: &[u8]) {
    write_varint(out, u64::from(field << 3 | 2));
    write_varint(out, data.len() as u64);
    out.extend_from_slice(data);
}

fn write_var(out: &mut Vec<u8>, field: u32, v: u64) {
    write_varint(out, u64::from(field << 3));
    write_varint(out, v);
}

fn encode_otp_msg(a: &QrAccount) -> Result<Vec<u8>, String> {
    let mut m = Vec::new();
    write_len(&mut m, 1, &secret_bytes(&a.secret)?);
    if !a.name.is_empty() {
        write_len(&mut m, 2, a.name.as_bytes());
    }
    if !a.issuer.is_empty() {
        write_len(&mut m, 3, a.issuer.as_bytes());
    }
    let algo = match a.algorithm.to_ascii_uppercase().replace('-', "").as_str() {
        "SHA256" => 2u64,
        "SHA512" => 3,
        _ => 1,
    };
    write_var(&mut m, 4, algo);
    write_var(&mut m, 5, if a.digits == 8 { 2 } else { 1 });
    write_var(&mut m, 6, 2);
    Ok(m)
}

pub fn migration_uri(accounts: &[QrAccount], batch_index: usize, batch_size: usize) -> Result<String, String> {
    let mut payload = Vec::new();
    for a in accounts {
        write_len(&mut payload, 1, &encode_otp_msg(a)?);
    }
    write_var(&mut payload, 2, 1);
    write_var(&mut payload, 3, batch_size as u64);
    write_var(&mut payload, 4, batch_index as u64);
    write_var(&mut payload, 5, 1);
    let b64 = base64::engine::general_purpose::STANDARD.encode(&payload);
    Ok(format!(
        "otpauth-migration://offline?data={}",
        b64.replace('+', "%2B")
    ))
}

pub fn qr_svg(text: &str) -> Result<String, String> {
    let code = qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::L)
        .map_err(|_| "二维码内容过长，请减少账号后重试")?;
    Ok(code
        .render::<qrcode::render::svg::Color>()
        .min_dimensions(240, 240)
        .dark_color(qrcode::render::svg::Color("#111111"))
        .light_color(qrcode::render::svg::Color("#ffffff"))
        .quiet_zone(true)
        .build())
}

pub fn migration_qrs(accounts: &[QrAccount]) -> Result<Vec<(String, usize)>, String> {
    if accounts.is_empty() {
        return Err("没有可导出的账号".into());
    }
    let mut size = 10usize.min(accounts.len());
    loop {
        let mut out = Vec::new();
        let chunks: Vec<&[QrAccount]> = accounts.chunks(size).collect();
        let total = chunks.len();
        let mut ok = true;
        for (i, chunk) in chunks.iter().enumerate() {
            let uri = migration_uri(chunk, i, total)?;
            match qr_svg(&uri) {
                Ok(svg) => out.push((svg, chunk.len())),
                Err(_) => {
                    ok = false;
                    break;
                }
            }
        }
        if ok {
            return Ok(out);
        }
        if size <= 1 {
            return Err("无法生成转移二维码".into());
        }
        size -= 1;
    }
}

pub fn parse_text(text: &str) -> Result<Vec<QrAccount>, String> {
    let t = text.trim();
    if t.starts_with('[') || t.starts_with('{') {
        return parse_json(t);
    }
    let mut all = Vec::new();
    for line in t.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if line.starts_with("otpauth") {
            all.extend(parse_uri(line)?);
        }
    }
    if all.is_empty() {
        Err("没有可导入的账号".into())
    } else {
        Ok(all)
    }
}

fn parse_json(text: &str) -> Result<Vec<QrAccount>, String> {
    let v: serde_json::Value = serde_json::from_str(text).map_err(|_| "JSON 无法解析")?;
    let arr = if v.is_array() {
        v.as_array().cloned().unwrap_or_default()
    } else {
        v.get("accounts")
            .and_then(|x| x.as_array())
            .cloned()
            .ok_or_else(|| "JSON 里没有 accounts".to_string())?
    };
    let mut out = Vec::new();
    for item in arr {
        if let Some(uri) = item.get("uri").and_then(|x| x.as_str()) {
            out.extend(parse_uri(uri)?);
            continue;
        }
        let secret = item["secret"].as_str().unwrap_or("").to_string();
        if secret.is_empty() {
            continue;
        }
        out.push(QrAccount {
            issuer: item["issuer"].as_str().unwrap_or("").into(),
            name: item["name"].as_str().unwrap_or("").into(),
            secret,
            algorithm: item["algorithm"].as_str().unwrap_or("SHA1").into(),
            digits: item["digits"].as_u64().unwrap_or(6) as u32,
            period: item["period"].as_u64().unwrap_or(30),
        });
    }
    if out.is_empty() {
        Err("JSON 里没有账号".into())
    } else {
        Ok(out)
    }
}
