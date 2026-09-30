// Run against the unpacked official npm 1.0.7 bundle; no npm installation needed.
// Upstream cryptographic implementation: Copyright (c) 2026 WeCom, MIT.
import fs from 'node:fs';
import vm from 'node:vm';
import crypto from 'node:crypto';
const bundle = fs.readFileSync(process.argv[2], 'utf8');
const core = bundle.slice(bundle.indexOf('const CRYPTO_CONSTANTS ='), bundle.indexOf('/** 默认导出 AiBot'));
const file = bundle.slice(bundle.indexOf('function decryptFile('), bundle.indexOf('/**\n * 默认日志实现'));
const context = {crypto, crypto$1: crypto, Buffer};
vm.runInNewContext(`${core}\n${file}\nthis.Crypto = WecomCrypto; this.decryptFile = decryptFile;`, context);
const key = Buffer.from(Array.from({length: 32}, (_, i) => i));
const encodingKey = key.toString('base64').replace(/=+$/, '');
const cipher = new context.Crypto('fixture-token', encodingKey, 'fixture-bot');
const text = '企业微信 SDK interoperability';
const result = cipher.encrypt(text, '123', '456');
if (cipher.decrypt(result.encrypt) !== text) throw new Error('Official round trip failed');
const plain = Buffer.from('media 中文');
const padding = 32 - plain.length % 32;
const encryptor = crypto.createCipheriv('aes-256-cbc', key, key.subarray(0, 16));
encryptor.setAutoPadding(false);
const encrypted = Buffer.concat([encryptor.update(Buffer.concat([plain, Buffer.alloc(padding, padding)])), encryptor.final()]);
if (!context.decryptFile(encrypted, encodingKey).equals(plain)) throw new Error('Official file decrypt failed');
console.log(JSON.stringify({source: '@wecom/aibot-node-sdk@1.0.7', gitHead: 'ea48edf7c99be0609fe9740050f4942f897a5d95', encodingKey, token: 'fixture-token', receiveId: 'fixture-bot', timestamp: '123', nonce: '456', text, ...result, mediaPlain: plain.toString('base64'), mediaEncrypted: encrypted.toString('base64')}, null, 2));
