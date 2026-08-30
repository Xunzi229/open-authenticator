use base64::Engine;
use data_encoding::BASE32_NOPAD;
use image::imageops::{self, FilterType};
use image::GrayImage;
use rand::RngCore;
use serde::Serialize;
use std::io::Cursor;
use url::Url;

const MAX_MIGRATION_BYTES: usize = 1024 * 1024;

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
    match u.host_str() {
        Some("totp") => {}
        Some("hotp") => return Err("暂不支持 HOTP 账号".into()),
        _ => return Err("不是 TOTP 验证器链接".into()),
    }
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
    let algo = u
        .query_pairs()
        .find(|(k, _)| k == "algorithm")
        .map(|(_, v)| v.to_ascii_uppercase().replace('-', ""))
        .unwrap_or_else(|| "SHA1".into());
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
    let secret = crate::totp::normalize_secret(&secret)?;
    let algo = crate::totp::validate_parameters(digits, &algo, period)?;
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
    if raw.len() > (MAX_MIGRATION_BYTES * 4 / 3) + 8 {
        return Err("migration 数据过大".into());
    }
    let pad = (4 - raw.len() % 4) % 4;
    raw.push_str(&"=".repeat(pad));
    let decoded = base64::engine::general_purpose::STANDARD
        .decode(&raw)
        .or_else(|_| base64::engine::general_purpose::URL_SAFE.decode(&raw))
        .map_err(|_| "migration 数据无法解码")?;
    if decoded.len() > MAX_MIGRATION_BYTES {
        return Err("migration 数据过大".into());
    }
    let mut accounts = Vec::new();
    let mut pos = 0usize;
    let mut batch_size = 1usize;
    let mut batch_index = 0usize;
    let mut batch_id = None;
    while pos < decoded.len() {
        let (tag, p) = read_varint(&decoded, pos)?;
        pos = p;
        let field = tag >> 3;
        let wire = tag & 7;
        if wire == 0 {
            let (value, p) = read_varint(&decoded, pos)?;
            pos = p;
            match field {
                3 => batch_size = value,
                4 => batch_index = value,
                5 => batch_id = Some(value),
                _ => {}
            }
        } else if wire == 2 {
            let (len, p) = read_varint(&decoded, pos)?;
            pos = p;
            let end = pos
                .checked_add(len)
                .filter(|end| *end <= decoded.len())
                .ok_or("migration 数据损坏")?;
            let value = &decoded[pos..end];
            pos = end;
            if field == 1 {
                if let Some(a) = parse_otp(value)? {
                    accounts.push(a);
                }
            }
        } else if wire == 1 {
            pos = pos
                .checked_add(8)
                .filter(|end| *end <= decoded.len())
                .ok_or("migration 数据损坏")?;
        } else if wire == 5 {
            pos = pos
                .checked_add(4)
                .filter(|end| *end <= decoded.len())
                .ok_or("migration 数据损坏")?;
        } else {
            return Err("migration 数据包含不支持的字段".into());
        }
    }
    if accounts.is_empty() {
        return Err("二维码里没有账号".into());
    }
    if batch_size == 0 || batch_index >= batch_size || (batch_size > 1 && batch_id.is_none()) {
        return Err("migration 批次信息无效".into());
    }
    Ok(accounts)
}

fn parse_otp(data: &[u8]) -> Result<Option<QrAccount>, String> {
    let mut pos = 0usize;
    let mut secret = Vec::new();
    let mut name = String::new();
    let mut issuer = String::new();
    let mut algorithm = 1u32;
    let mut digits = 1u32;
    let mut otp_type = 0u32;
    while pos < data.len() {
        let (tag, p) = read_varint(data, pos)?;
        pos = p;
        let field = tag >> 3;
        let wire = tag & 7;
        if wire == 0 {
            let (v, p) = read_varint(data, pos)?;
            pos = p;
            if field == 4 {
                algorithm = v as u32;
            }
            if field == 5 {
                digits = v as u32;
            }
            if field == 6 {
                otp_type = v as u32;
            }
        } else if wire == 2 {
            let (len, p) = read_varint(data, pos)?;
            pos = p;
            let end = pos
                .checked_add(len)
                .filter(|end| *end <= data.len())
                .ok_or("migration 账号数据损坏")?;
            let value = &data[pos..end];
            pos = end;
            match field {
                1 => secret = value.to_vec(),
                2 => name = String::from_utf8_lossy(value).into_owned(),
                3 => issuer = String::from_utf8_lossy(value).into_owned(),
                _ => {}
            }
        } else if wire == 1 {
            pos = pos
                .checked_add(8)
                .filter(|end| *end <= data.len())
                .ok_or("migration 账号数据损坏")?;
        } else if wire == 5 {
            pos = pos
                .checked_add(4)
                .filter(|end| *end <= data.len())
                .ok_or("migration 账号数据损坏")?;
        } else {
            return Err("migration 账号包含不支持的字段".into());
        }
    }
    if secret.is_empty() {
        return Ok(None);
    }
    if otp_type == 1 {
        return Err("迁移数据包含暂不支持的 HOTP 账号".into());
    }
    if otp_type != 0 && otp_type != 2 {
        return Err("迁移数据包含未知的 OTP 类型".into());
    }
    // Google proto: 1=SHA1 2=SHA256 3=SHA512；DigitCount 1=6 2=8，也兼容直接写 6/8
    let algo = match algorithm {
        1 => "SHA1",
        2 => "SHA256",
        3 => "SHA512",
        _ => return Err("迁移数据包含未知的算法".into()),
    };
    let d = match digits {
        1 | 6 => 6,
        2 | 8 => 8,
        _ => return Err("迁移数据包含未知的验证码位数".into()),
    };
    Ok(Some(QrAccount {
        issuer,
        name,
        secret: BASE32_NOPAD.encode(&secret),
        algorithm: algo.into(),
        digits: d,
        period: 30,
    }))
}

fn read_varint(data: &[u8], mut pos: usize) -> Result<(usize, usize), String> {
    let mut result = 0usize;
    let mut shift = 0;
    while pos < data.len() {
        let b = data[pos];
        pos += 1;
        let part = ((b & 0x7f) as usize)
            .checked_shl(shift)
            .ok_or_else(|| "migration varint 溢出".to_string())?;
        result |= part;
        if b & 0x80 == 0 {
            return Ok((result, pos));
        }
        shift += 7;
        if shift > 63 {
            return Err("migration varint 溢出".into());
        }
    }
    Err("migration varint 截断".into())
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
    if bytes.len() > 10 * 1024 * 1024 {
        return Err("二维码图片不能超过 10 MiB".into());
    }
    let mut reader = image::ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .map_err(|_| "无法读取图片")?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(8192);
    limits.max_image_height = Some(8192);
    limits.max_alloc = Some(128 * 1024 * 1024);
    reader.limits(limits);
    let img = reader.decode().map_err(|_| "无法读取图片")?;
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

fn migration_uri(
    accounts: &[QrAccount],
    batch_index: usize,
    batch_size: usize,
    batch_id: u64,
) -> Result<String, String> {
    let mut payload = Vec::new();
    for a in accounts {
        write_len(&mut payload, 1, &encode_otp_msg(a)?);
    }
    write_var(&mut payload, 2, 1);
    write_var(&mut payload, 3, batch_size as u64);
    write_var(&mut payload, 4, batch_index as u64);
    write_var(&mut payload, 5, batch_id);
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
    if accounts.iter().any(|account| account.period != 30) {
        return Err("Google 转移二维码仅支持 30 秒周期账号".into());
    }
    let batch_id = rand::thread_rng().next_u64();
    let mut size = 10usize.min(accounts.len());
    loop {
        let mut out = Vec::new();
        let chunks: Vec<&[QrAccount]> = accounts.chunks(size).collect();
        let total = chunks.len();
        let mut ok = true;
        for (i, chunk) in chunks.iter().enumerate() {
            let uri = migration_uri(chunk, i, total, batch_id)?;
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_hotp_uri_instead_of_generating_wrong_codes() {
        let result = parse_uri("otpauth://hotp/Example:user?secret=JBSWY3DPEHPK3PXP&counter=1");
        assert_eq!(result.unwrap_err(), "暂不支持 HOTP 账号");
    }

    #[test]
    fn accepts_totp_uri() {
        let result = parse_uri("otpauth://totp/Example:user?secret=JBSWY3DPEHPK3PXP").unwrap();
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].issuer, "Example");
    }

    #[test]
    fn rejects_unknown_otpauth_algorithm_instead_of_silently_using_sha1() {
        let result = parse_uri("otpauth://totp/Example:user?secret=JBSWY3DPEHPK3PXP&algorithm=MD5");
        assert_eq!(result.unwrap_err(), "不支持的验证码算法");
    }

    #[test]
    fn rejects_truncated_migration_payload() {
        let encoded = base64::engine::general_purpose::STANDARD.encode([0x0a, 0x7f, 0x01]);
        let result = parse_uri(&format!("otpauth-migration://offline?data={encoded}"));
        assert!(result.is_err());
    }

    #[test]
    fn refuses_custom_period_google_migration_export() {
        let account = QrAccount {
            issuer: "Example".into(),
            name: "user".into(),
            secret: "JBSWY3DPEHPK3PXP".into(),
            algorithm: "SHA1".into(),
            digits: 6,
            period: 60,
        };
        assert!(migration_qrs(&[account]).is_err());
    }

    #[test]
    fn rejects_hotp_in_google_migration_data() {
        let mut message = Vec::new();
        write_len(&mut message, 1, b"secret");
        write_var(&mut message, 6, 1);
        assert_eq!(
            parse_otp(&message).unwrap_err(),
            "迁移数据包含暂不支持的 HOTP 账号"
        );
    }
}
