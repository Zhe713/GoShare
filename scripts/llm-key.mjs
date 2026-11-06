#!/usr/bin/env node
/**
 * GoShare 本地加密保存大模型 API Key（不落明文、不进仓库）
 *
 * 用法：
 *   node scripts/llm-key.mjs set      # 交互式输入 key（不回显），加密保存并切到 http 模式
 *   node scripts/llm-key.mjs show     # 查看当前配置（key 打码）
 *   node scripts/llm-key.mjs clear    # 删除本地配置（回到 mock 模式）
 *
 * 存放位置（均在仓库之外）：
 *   ~/.goshare/goshare-llm.properties  → goshare.llm.mode / goshare.llm.api-key=ENC:...
 *   ~/.goshare/master.key              → 32 字节主密钥（base64），首次 set 时自动生成
 *
 * 加密算法与后端 LlmSecretCrypto 严格一致：AES-256-GCM，密文 = base64(iv[12]|tag[16]|ct)。
 * 安全边界：防的是"明文被顺手拷走 / 误提交"，不是对抗本机管理员；生产请用 KMS。
 */
import { createCipheriv, randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync, rmSync, chmodSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { stdin, stdout } from 'node:process'

const DIR = join(homedir(), '.goshare')
const SECRET_FILE = join(DIR, 'goshare-llm.properties')
const MASTER_FILE = join(DIR, 'master.key')

/** 读取主密钥（不存在则生成 32 字节随机数） */
function ensureMasterKey() {
  mkdirSync(DIR, { recursive: true })
  if (!existsSync(MASTER_FILE)) {
    const key = randomBytes(32)
    writeFileSync(MASTER_FILE, key.toString('base64') + '\n', { mode: 0o600 })
    try {
      chmodSync(MASTER_FILE, 0o600)
    } catch {
      /* Windows 忽略权限位 */
    }
    console.log(`已生成主密钥：${MASTER_FILE}`)
    return key.toString('base64')
  }
  const raw = readFileSync(MASTER_FILE, 'utf8').trim()
  const key = Buffer.from(raw, 'base64')
  if (key.length !== 32) {
    throw new Error(`主密钥格式不正确（应为 32 字节 base64）：${MASTER_FILE}`)
  }
  return raw
}

/** AES-256-GCM 加密，产出 ENC:base64(iv|tag|ct)，与后端解密实现对齐 */
function encrypt(plain, masterKeyBase64) {
  const key = Buffer.from(masterKeyBase64, 'base64')
  const iv = randomBytes(12)
  const cipher = createCipheriv('aes-256-gcm', key, iv)
  const ct = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()])
  const tag = cipher.getAuthTag()
  return 'ENC:' + Buffer.concat([iv, tag, ct]).toString('base64')
}

/** 交互式读取（终端回显关闭；非 TTY 时从 stdin 读一行，便于管道/脚本） */
function readSecret(prompt) {
  return new Promise((resolve, reject) => {
    stdout.write(prompt)
    if (!stdin.isTTY) {
      let buf = ''
      stdin.setEncoding('utf8')
      stdin.on('data', (d) => (buf += d))
      stdin.on('end', () => resolve(buf.split(/\r?\n/)[0].trim()))
      stdin.on('error', reject)
      return
    }
    stdin.setRawMode(true)
    stdin.resume()
    stdin.setEncoding('utf8')
    let value = ''
    const onData = (ch) => {
      switch (ch) {
        case '\r':
        case '\n':
        case '\u0004':
          stdin.setRawMode(false)
          stdin.pause()
          stdin.removeListener('data', onData)
          stdout.write('\n')
          resolve(value.trim())
          break
        case '\u0003':
          stdin.setRawMode(false)
          stdin.pause()
          stdout.write('\n已取消\n')
          process.exit(130)
          break
        case '\u007f':
        case '\b':
          if (value.length > 0) {
            value = value.slice(0, -1)
            stdout.write('\b \b')
          }
          break
        default:
          if (ch >= ' ') {
            value += ch
            stdout.write('*')
          }
      }
    }
    stdin.on('data', onData)
  })
}

function readProps() {
  if (!existsSync(SECRET_FILE)) return {}
  const out = {}
  for (const line of readFileSync(SECRET_FILE, 'utf8').split(/\r?\n/)) {
    const t = line.trim()
    if (!t || t.startsWith('#')) continue
    const i = t.indexOf('=')
    if (i > 0) out[t.slice(0, i).trim()] = t.slice(i + 1).trim()
  }
  return out
}

const mask = (s) => (s.length <= 8 ? '****' : `${s.slice(0, 4)}****${s.slice(-4)}`)

async function cmdSet() {
  const key = await readSecret('请粘贴大模型 API Key（输入不回显，回车确认）：')
  if (!key) {
    console.error('未输入内容，已取消。')
    process.exit(1)
  }
  const masterKey = ensureMasterKey()
  const url = process.env.GOSHARE_LLM_HTTP_URL || 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'
  const content = [
    '# GoShare 本地 LLM 配置（由 scripts/llm-key.mjs 生成，请勿提交到仓库）',
    'goshare.llm.mode=http',
    `goshare.llm.http-url=${url}`,
    `goshare.llm.api-key=${encrypt(key, masterKey)}`,
    '',
  ].join('\n')
  mkdirSync(DIR, { recursive: true })
  writeFileSync(SECRET_FILE, content, { mode: 0o600 })
  try {
    chmodSync(SECRET_FILE, 0o600)
  } catch {
    /* Windows 忽略权限位 */
  }
  console.log(`\n已加密保存：${SECRET_FILE}`)
  console.log(`key 摘要：${mask(key)}   模式：http   端点：${url}`)
  console.log('重启 assistant 服务后生效（无需环境变量）。')
}

function cmdShow() {
  const props = readProps()
  const enc = props['goshare.llm.api-key'] || ''
  console.log(`配置文件：${SECRET_FILE} ${existsSync(SECRET_FILE) ? '(存在)' : '(不存在)'}`)
  console.log(`主密钥  ：${MASTER_FILE} ${existsSync(MASTER_FILE) ? '(存在)' : '(不存在)'}`)
  console.log(`模式    ：${props['goshare.llm.mode'] || '(未设置，默认 mock)'}`)
  console.log(`端点    ：${props['goshare.llm.http-url'] || '(未设置)'}`)
  console.log(`api-key ：${enc ? (enc.startsWith('ENC:') ? 'ENC: 密文（' + mask(enc) + '）' : '明文（建议重新 set 加密存储）') : '(未设置)'}`)
  if (process.env.GOSHARE_LLM_API_KEY) {
    console.log('注意    ：检测到环境变量 GOSHARE_LLM_API_KEY，其优先级高于本地文件。')
  }
}

function cmdClear() {
  if (existsSync(SECRET_FILE)) {
    rmSync(SECRET_FILE)
    console.log(`已删除 ${SECRET_FILE}（回到 mock 模式）。主密钥保留在 ${MASTER_FILE}。`)
  } else {
    console.log('本地未配置 LLM Key，无需清理。')
  }
}

const cmd = process.argv[2] || 'show'
if (cmd === 'set') await cmdSet()
else if (cmd === 'show') cmdShow()
else if (cmd === 'clear') cmdClear()
else {
  console.log('用法：node scripts/llm-key.mjs <set|show|clear>')
  process.exit(1)
}
