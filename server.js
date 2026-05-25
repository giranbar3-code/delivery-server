const express = require('express');
const cors = require('cors');
const path = require('path');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const dns = require('dns');
const crypto = require('crypto');
const http = require('http');
const { WebSocketServer } = require('ws');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// عملاء WebSocket حسب المكتب أو السائق
const wsClients = new Map(); // officeId → Set<WebSocket>
const wsDriverClients = new Map(); // driverPhone → Set<WebSocket>

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost');
  const officeId = parseInt(url.searchParams.get('officeId')) || 0;
  const driverPhone = url.searchParams.get('driverPhone') || '';

  if (driverPhone) {
    if (!wsDriverClients.has(driverPhone)) wsDriverClients.set(driverPhone, new Set());
    wsDriverClients.get(driverPhone).add(ws);
  } else {
    if (!wsClients.has(officeId)) wsClients.set(officeId, new Set());
    wsClients.get(officeId).add(ws);
  }

  ws.on('close', () => {
    if (driverPhone) {
      wsDriverClients.get(driverPhone)?.delete(ws);
    } else {
      wsClients.get(officeId)?.delete(ws);
    }
  });
});

function notifyClients(officeId, data) {
  const clients = wsClients.get(officeId);
  if (clients) {
    const msg = JSON.stringify(data);
    for (const ws of clients) {
      if (ws.readyState === 1) ws.send(msg);
    }
  }
}

function notifyDriver(driverPhone, data) {
  const clients = wsDriverClients.get(driverPhone);
  if (clients) {
    const msg = JSON.stringify(data);
    for (const ws of clients) {
      if (ws.readyState === 1) ws.send(msg);
    }
  }
}

const PORT = process.env.PORT || 3000;
const DATABASE_URL = process.env.DATABASE_URL;
const API_KEY = process.env.API_KEY;
const SITE_KEY = process.env.SITE_KEY || ''; // مفتاح إضافي للنموذج العام
const SETUP_KEY_HASH = process.env.SETUP_KEY_HASH || '7ac3c5cc0fb2510140c7d1861db2e8d3d65f223bbd23fd254d584c81ab07b109';
const SETUP_KEY_SALT = process.env.SETUP_KEY_SALT || '2a3b4c5d6e7f102132435465768798a9'; // hex
const MAX_MEMORY_ORDERS = 500;
const MAX_ORDERS_PER_PHONE_PER_HOUR = 5;
const CSRF_TTL = 3600000; // 1 ساعة

// تخزين مؤقت لرموز CSRF
const csrfTokens = new Map();
function generateCsrfToken() {
  const token = crypto.randomBytes(32).toString('hex');
  csrfTokens.set(token, Date.now());
  // تنظيف الرموز منتهية الصلاحية كل 5 دقائق
  if (csrfTokens.size % 50 === 0) {
    const now = Date.now();
    for (const [t, time] of csrfTokens) {
      if (now - time > CSRF_TTL) csrfTokens.delete(t);
    }
  }
  return token;
}
function validateCsrfToken(token) {
  if (!token || !csrfTokens.has(token)) return false;
  if (Date.now() - csrfTokens.get(token) > CSRF_TTL) {
    csrfTokens.delete(token);
    return false;
  }
  csrfTokens.delete(token); // استهلاك لمرة واحدة
  return true;
}

if (!API_KEY) {
  console.error('❌ متغير API_KEY غير مضبوط — أوقف التشغيل');
  process.exit(1);
}

// تصحيح IP خلف Proxy Render — ضروري لـ Rate Limiting لكل زبون على حدة
app.set('trust proxy', 1);

// رؤوس أمان
app.use(helmet({
  contentSecurityPolicy: {
    directives: {
      defaultSrc: ["'self'"],
      baseUri: ["'self'"],
      fontSrc: ["'self'", "https:", "data:"],
      formAction: ["'self'"],
      frameAncestors: ["'self'"],
      imgSrc: ["'self'", "data:"],
      objectSrc: ["'none'"],
      scriptSrc: ["'self'", "'unsafe-inline'"],
      scriptSrcAttr: ["'none'"],
      styleSrc: ["'self'", "https:", "'unsafe-inline'"],
      upgradeInsecureRequests: [],
    },
  },
}));

// CORS مقيد
const allowedOrigins = process.env.ALLOWED_ORIGINS
  ? process.env.ALLOWED_ORIGINS.split(',').map(s => s.trim())
  : [];
const corsAllowAll = allowedOrigins.includes('*');
app.use(cors({
  origin: (origin, callback) => {
    // بدون Origin (تطبيق Android، Postman، curl) — مسموح دائماً
    if (!origin) return callback(null, true);
    // التصريح الصريح بـ * — يسمح للكل
    if (corsAllowAll) return callback(null, true);
    // النطاق موجود في القائمة المسموحة
    if (allowedOrigins.includes(origin)) return callback(null, true);
    // غير مسموح
    callback(null, false);
  }
}));

// Rate Limiting عام — لكل IP (trust proxy لازم يكون مضبوط عشان يشتغل صح)
const globalLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 100,
  message: { error: 'طلبات كثيرة جداً، حاول لاحقاً', code: 'RATE_LIMITED' }
});
app.use(globalLimiter);

// Rate Limiting مشدد على API الطلبات
const apiLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 30,
  message: { error: 'طلبات كثيرة جداً على API', code: 'RATE_LIMITED' }
});
app.use('/api', apiLimiter);

// Rate Limiting خاص بإنشاء الطلبات العامة — لكل IP
const publicOrderLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 10,
  message: { error: 'طلبات كثيرة جداً، حاول بعد دقيقة', code: 'RATE_LIMITED_IP' }
});

app.use(express.json({ limit: '1mb' }));

// المسارات العامة التي لا تحتاج API Key
const publicApiPaths = ['/api/status', '/api/csrf-token', '/api/verify-setup'];
const isPublicPath = (req) => {
  return publicApiPaths.includes(req.path) ||
    (req.method === 'POST' && req.path === '/api/orders') ||
    req.path.startsWith('/api/driver/');
};

// التحقق من مفتاح API
app.use((req, res, next) => {
  if (req.path.startsWith('/api/') && !isPublicPath(req)) {
    const providedKey = req.headers['x-api-key'];
    if (!providedKey || providedKey !== API_KEY) {
      return res.status(401).json({ error: 'مفتاح API غير صالح' });
    }
  }
  next();
});

let memoryOrders = [];
let memoryId = 1;
let useDb = false;
let pool = null;

// تخزين السائقين (للـ In-Memory)
let memoryDrivers = []; // { id, name, phone, pinHash, officeId }
let driverTokens = new Map(); // token → driverPhone
let memoryDriverId = 1;

// محاولة حل DNS لتشخيص المشكلة
function resolveDbHost(url) {
  try {
    const match = url.match(/@([^:]+)/);
    if (match) {
      const host = match[1];
      console.log(`🔍 محاولة حل DNS للمضيف: ${host}`);
      dns.resolve4(host, (err, addresses) => {
        if (err) {
          console.log(`⚠️ فشل حل DNS: ${err.code}`);
        } else {
          console.log(`✅ DNS تم الحل: ${host} → ${addresses.join(', ')}`);
        }
      });
    }
  } catch (e) {}
}

// محاولة الاتصال بـ PostgreSQL مع إعادة المحاولة
async function connectToDatabase(retries = 5, delay = 3000) {
  if (!DATABASE_URL) return null;

  resolveDbHost(DATABASE_URL);

  for (let attempt = 1; attempt <= retries; attempt++) {
    try {
      const { Pool } = require('pg');
      const p = new Pool({
        connectionString: DATABASE_URL,
        ssl: { rejectUnauthorized: true },
        connectionTimeoutMillis: 10000,
        query_timeout: 10000,
        family: 4
      });
      await p.query('SELECT 1');
      console.log(`✅ PostgreSQL متصل (محاولة ${attempt})`);
      return p;
    } catch (err) {
      console.log(`⚠️ PostgreSQL غير متاح (محاولة ${attempt}/${retries}): ${err.message}`);
      if (attempt < retries) {
        console.log(`⏳ إعادة المحاولة بعد ${delay/1000} ثوانٍ...`);
        await new Promise(r => setTimeout(r, delay));
      }
    }
  }
  return null;
}

// تهيئة قاعدة البيانات
async function initDatabase(p) {
  try {
    await p.query(`CREATE TABLE IF NOT EXISTS orders (
      id SERIAL PRIMARY KEY, customer_name TEXT NOT NULL, customer_phone TEXT NOT NULL,
      order_type TEXT NOT NULL, quantity INTEGER NOT NULL DEFAULT 1, delivery_address TEXT NOT NULL,
      location_url TEXT DEFAULT '', notes TEXT DEFAULT '', items TEXT DEFAULT '[]',
      verified BOOLEAN DEFAULT false, client_ip TEXT DEFAULT '',
      synced BOOLEAN DEFAULT false, created_at TIMESTAMPTZ DEFAULT NOW(),
      office_id BIGINT NOT NULL DEFAULT 0,
      driver_name TEXT DEFAULT '', driver_phone TEXT DEFAULT '',
      delivery_status TEXT DEFAULT 'PENDING', purchase_price DOUBLE PRECISION DEFAULT 0,
      delivery_price DOUBLE PRECISION DEFAULT 0
    )`);
    await p.query(`ALTER TABLE orders ADD COLUMN IF NOT EXISTS office_id BIGINT NOT NULL DEFAULT 0`).catch(e => {});
    await p.query(`ALTER TABLE orders ADD COLUMN IF NOT EXISTS driver_name TEXT DEFAULT ''`).catch(e => {});
    await p.query(`ALTER TABLE orders ADD COLUMN IF NOT EXISTS driver_phone TEXT DEFAULT ''`).catch(e => {});
    await p.query(`ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_status TEXT DEFAULT 'PENDING'`).catch(e => {});
    await p.query(`ALTER TABLE orders ADD COLUMN IF NOT EXISTS purchase_price DOUBLE PRECISION DEFAULT 0`).catch(e => {});
    await p.query(`ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_price DOUBLE PRECISION DEFAULT 0`).catch(e => {});
    await p.query(`ALTER TABLE orders ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW()`).catch(e => {});

    // جدول السائقين
    await p.query(`CREATE TABLE IF NOT EXISTS drivers (
      id SERIAL PRIMARY KEY, name TEXT NOT NULL, phone TEXT NOT NULL UNIQUE,
      pin_hash TEXT NOT NULL, office_id BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ DEFAULT NOW()
    )`);
    console.log('✅ جداول PostgreSQL جاهزة');
  } catch (e) {
    console.error('❌ فشل تهيئة الجداول:', e.message);
    throw e;
  }
}

// بدء الاتصال — نحاول 5 مرات قبل الرجوع للذاكرة المؤقتة
(async () => {
  pool = await connectToDatabase(5, 3000);
  if (pool) {
    try {
      await initDatabase(pool);
      useDb = true;
    } catch (e) {
      console.log('⚠️ فشل تهيئة الجداول، استخدام التخزين المؤقت');
    }
  } else {
    console.log('⚠️ PostgreSQL غير متاح بعد 5 محاولات، استخدام التخزين المؤقت');
  }

  // محاولة إعادة الاتصال في الخلفية كل 30 ثانية
  if (!useDb) {
    setInterval(async () => {
      console.log('🔄 محاولة إعادة الاتصال بـ PostgreSQL...');
      const p = await connectToDatabase(1, 0);
      if (p) {
        try {
          await initDatabase(p);
          pool = p;
          useDb = true;
          console.log('✅ تم التبديل إلى PostgreSQL بعد إعادة الاتصال');
        } catch (e) {
          await p.end().catch(() => {});
        }
      }
    }, 30000);
  }
})();

// نقطة لجلب رمز CSRF (للنماذج الديناميكية)
app.get('/api/csrf-token', (req, res) => {
  const token = generateCsrfToken();
  res.json({ csrfToken: token, siteKey: SITE_KEY });
});

app.get('/', (req, res) => {
  const csrfToken = generateCsrfToken();
  const fs = require('fs');
  let html = fs.readFileSync(path.join(__dirname, 'public', 'index.html'), 'utf8');
  html = html.replace(/\{\{CSRF_TOKEN\}\}/g, csrfToken);
  html = html.replace(/\{\{SITE_KEY\}\}/g, SITE_KEY);
  html = html.replace(/\{\{OFFICE_ID\}\}/g, '0');
  res.send(html);
});

app.get('/office/:id', (req, res) => {
  const officeId = parseInt(req.params.id) || 0;
  const csrfToken = generateCsrfToken();
  const fs = require('fs');
  let html = fs.readFileSync(path.join(__dirname, 'public', 'index.html'), 'utf8');
  html = html.replace(/\{\{CSRF_TOKEN\}\}/g, csrfToken);
  html = html.replace(/\{\{SITE_KEY\}\}/g, SITE_KEY);
  html = html.replace(/\{\{OFFICE_ID\}\}/g, officeId);
  res.send(html);
});

app.get('/api/status', (req, res) => {
  res.json({ status: 'ok' });
});

// التحقق من مفتاح التفعيل (جهة الخادم)
app.post('/api/verify-setup', (req, res) => {
  try {
    const { key } = req.body;
    if (!key || typeof key !== 'string' || key.length > 100) {
      return res.status(400).json({ valid: false, error: 'مفتاح غير صالح' });
    }
    const saltBytes = Buffer.from(SETUP_KEY_SALT, 'hex');
    const derivedKey = crypto.pbkdf2Sync(key.trim(), saltBytes, 600000, 32, 'sha256');
    const hashHex = derivedKey.toString('hex');
    const valid = crypto.timingSafeEqual(
      Buffer.from(hashHex, 'hex'),
      Buffer.from(SETUP_KEY_HASH, 'hex')
    );
    res.json({ valid });
  } catch (err) {
    console.error('❌ فشل التحقق من مفتاح التفعيل:', err.message);
    res.status(500).json({ valid: false, error: 'خطأ في الخادم' });
  }
});

app.post('/api/orders', publicOrderLimiter, async (req, res) => {
  try {
    const { customerName, customerPhone, orderType, quantity, deliveryAddress, locationUrl, notes, items, honeypot, pageLoad, officeId, csrfToken, siteKey } = req.body;

    // 🛡 التحقق من رمز CSRF
    if (!validateCsrfToken(csrfToken)) {
      return res.status(403).json({ error: 'رمز الأمان غير صالح أو منتهي الصلاحية', code: 'CSRF_INVALID' });
    }

    // 🛡 التحقق من مفتاح الموقع (اختياري عبر SITE_KEY)
    if (SITE_KEY && siteKey !== SITE_KEY) {
      return res.status(403).json({ error: 'مفتاح الموقع غير صالح', code: 'SITE_KEY_INVALID' });
    }

    // 🛡 التحقق من الروبوتات
    if (honeypot) {
      return res.status(400).json({ error: 'طلب غير صالح', code: 'BOT_DETECTED' });
    }
    if (pageLoad && Date.now() - pageLoad < 3000) {
      return res.status(400).json({ error: 'الرجاء الانتظار قليلاً قبل الإرسال', code: 'TOO_FAST' });
    }

    const MAX_LEN = 500;
    const PHONE_MAX_LEN = 30;
    if (!customerName || !customerPhone || !deliveryAddress || !locationUrl || !/^https?:\/\//i.test(locationUrl)) {
      return res.status(400).json({ error: 'اسم الزبون، رقم الهاتف، العنوان ورابط الموقع إجبارية' });
    }
    if (customerName.length > MAX_LEN || (customerPhone && customerPhone.length > PHONE_MAX_LEN) || deliveryAddress.length > MAX_LEN || (notes && notes.length > MAX_LEN)) {
      return res.status(400).json({ error: 'النص طويل جداً' });
    }

    // 🛡 التحقق من عدد الطلبات لنفس الرقم
    const oneHourAgo = new Date(Date.now() - 3600000);
    let recentCount = 0;
    if (useDb) {
      const r = await pool.query(
        'SELECT COUNT(*) as cnt FROM orders WHERE customer_phone = $1 AND created_at > $2',
        [customerPhone, oneHourAgo]
      );
      recentCount = parseInt(r.rows[0].cnt);
    } else {
      recentCount = memoryOrders.filter(o =>
        o.customerPhone === customerPhone && new Date(o.createdAt) > oneHourAgo
      ).length;
    }
    if (recentCount >= MAX_ORDERS_PER_PHONE_PER_HOUR) {
      return res.status(429).json({
        error: `تم تجاوز الحد المسموح من الطلبات لهذا الرقم (${MAX_ORDERS_PER_PHONE_PER_HOUR} في الساعة)`,
        code: 'RATE_LIMITED'
      });
    }

    const clientIp = req.headers['x-forwarded-for']?.split(',')[0]?.trim() || req.socket.remoteAddress || '';
    const itemsJson = JSON.stringify(items || []);
    const finalOrderType = orderType || (items && items.length ? items.map(i => i.name).join('، ') : 'طلب');
    const finalQuantity = quantity || (items ? items.reduce((s, i) => s + (parseInt(i.quantity) || 1), 0) : 1);
    if (finalQuantity < 1 || finalQuantity > 9999) {
      return res.status(400).json({ error: 'الكمية خارج النطاق المسموح' });
    }
    const isVerified = recentCount === 0;
    const finalOfficeId = parseInt(officeId) || 0;

    if (useDb) {
      const r = await pool.query(
        `INSERT INTO orders (customer_name,customer_phone,order_type,quantity,delivery_address,location_url,notes,items,verified,client_ip,office_id)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) RETURNING *`,
        [customerName, customerPhone, finalOrderType, finalQuantity, deliveryAddress, locationUrl||'', notes||'', itemsJson, isVerified, clientIp, finalOfficeId]
      );
      const order = formatOrder(r.rows[0]);
      notifyClients(finalOfficeId, { type: 'new_order', order });
      return res.status(201).json({ message: 'تم الاستلام', order });
    }

    const now = new Date().toISOString();
    const order = {
      id: memoryId++, customerName, customerPhone, orderType: finalOrderType, quantity: finalQuantity,
      deliveryAddress, locationUrl: locationUrl||'', notes: notes||'', items: items || [],
      verified: isVerified, clientIp, officeId: finalOfficeId,
      synced: false, createdAt: now, updatedAt: now
    };
    memoryOrders.unshift(order);
    if (memoryOrders.length > MAX_MEMORY_ORDERS) memoryOrders.length = MAX_MEMORY_ORDERS;
    res.status(201).json({ message: 'تم الاستلام', order });
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: 'خطأ في الخادم' });
  }
});

app.get('/api/orders', async (req, res) => {
  try {
    const since = req.query.since || '1970-01-01';
    const officeId = parseInt(req.query.officeId) || 0;
    if (useDb) {
      const query = officeId > 0
        ? `SELECT * FROM orders WHERE GREATEST(created_at, COALESCE(updated_at, created_at)) > $1 AND office_id = $2 ORDER BY GREATEST(created_at, COALESCE(updated_at, created_at)) DESC`
        : `SELECT * FROM orders WHERE GREATEST(created_at, COALESCE(updated_at, created_at)) > $1 ORDER BY GREATEST(created_at, COALESCE(updated_at, created_at)) DESC`;
      const params = officeId > 0 ? [since, officeId] : [since];
      const r = await pool.query(query, params);
      return res.json(r.rows.map(formatOrder));
    }
    const sinceTime = new Date(since).getTime() || 0;
    let filtered = memoryOrders.filter(o => {
      const orderTime = new Date(o.createdAt).getTime();
      const updateTime = o.updatedAt ? new Date(o.updatedAt).getTime() : orderTime;
      return Math.max(orderTime, updateTime) > sinceTime;
    });
    if (officeId > 0) {
      filtered = filtered.filter(o => o.officeId === officeId);
    }
    res.json(filtered);
  } catch (err) {
    console.error(err); res.status(500).json({ error: 'خطأ' });
  }
});

app.post('/api/orders/sync', async (req, res) => {
  try {
    const { ids } = req.body;
    if (!ids || !Array.isArray(ids)) return res.status(400).json({ error: 'ids مطلوب' });
    if (useDb) {
      const r = await pool.query('UPDATE orders SET synced=true WHERE id=ANY($1::int[])', [ids]);
      return res.json({ message: 'تم', count: r.rowCount });
    }
    let count = 0;
    memoryOrders.forEach(o => { if (ids.includes(o.id)) { o.synced = true; count++; } });
    res.json({ message: 'تم', count });
  } catch (err) { console.error(err); res.status(500).json({ error: 'خطأ' }); }
});

function formatOrder(row) {
  let items = [];
  try { items = JSON.parse(row.items || '[]'); } catch(e) {}
  return {
    id: row.id, customerName: row.customer_name, customerPhone: row.customer_phone,
    orderType: row.order_type, quantity: row.quantity, deliveryAddress: row.delivery_address,
    locationUrl: row.location_url||'', notes: row.notes||'', items,
    verified: row.verified || false, clientIp: row.client_ip || '',
    synced: row.synced, createdAt: row.created_at,
    updatedAt: row.updated_at || row.created_at,
    officeId: row.office_id || 0,
    driverName: row.driver_name || '', driverPhone: row.driver_phone || '',
    deliveryStatus: row.delivery_status || 'PENDING',
    purchasePrice: parseFloat(row.purchase_price) || 0,
    deliveryPrice: parseFloat(row.delivery_price) || 0
  };
}

// ════════════════════════════════════════════
//  نقاط API خاصة بتطبيق السائق
// ════════════════════════════════════════════

// تسجيل سائق جديد (يستخدم من تطبيق المكتب لإضافة السائقين للخادم)
app.post('/api/drivers', async (req, res) => {
  try {
    const { name, phone, pin, officeId } = req.body;
    if (!name || !phone || !pin) {
      return res.status(400).json({ error: 'الاسم، الهاتف والرمز السري مطلوب' });
    }
    const pinHash = crypto.createHash('sha256').update(pin).digest('hex');
    const finalOfficeId = parseInt(officeId) || 0;

    if (useDb) {
      const existing = await pool.query('SELECT id FROM drivers WHERE phone = $1', [phone]);
      if (existing.rows.length > 0) {
        return res.status(409).json({ error: 'سائق بهذا الرقم موجود مسبقاً' });
      }
      const r = await pool.query(
        'INSERT INTO drivers (name, phone, pin_hash, office_id) VALUES ($1,$2,$3,$4) RETURNING id, name, phone, office_id',
        [name, phone, pinHash, finalOfficeId]
      );
      return res.status(201).json({ driver: r.rows[0] });
    }

    if (memoryDrivers.find(d => d.phone === phone)) {
      return res.status(409).json({ error: 'سائق بهذا الرقم موجود مسبقاً' });
    }
    const driver = { id: memoryDriverId++, name, phone, pinHash, officeId: finalOfficeId };
    memoryDrivers.push(driver);
    res.status(201).json({ driver: { id: driver.id, name: driver.name, phone: driver.phone, office_id: driver.officeId } });
  } catch (err) {
    console.error(err); res.status(500).json({ error: 'خطأ في الخادم' });
  }
});

// مصادقة السائق (تسجيل دخول)
app.post('/api/driver/auth', async (req, res) => {
  try {
    const { phone, pin } = req.body;
    if (!phone || !pin) {
      return res.status(400).json({ error: 'رقم الهاتف والرمز السري مطلوب' });
    }
    const pinHash = crypto.createHash('sha256').update(pin).digest('hex');

    let driver = null;
    if (useDb) {
      const r = await pool.query('SELECT id, name, phone, office_id FROM drivers WHERE phone = $1 AND pin_hash = $2', [phone, pinHash]);
      if (r.rows.length === 0) return res.status(401).json({ error: 'رقم الهاتف أو الرمز السري خطأ' });
      driver = r.rows[0];
    } else {
      driver = memoryDrivers.find(d => d.phone === phone && d.pinHash === pinHash);
      if (!driver) return res.status(401).json({ error: 'رقم الهاتف أو الرمز السري خطأ' });
      driver = { id: driver.id, name: driver.name, phone: driver.phone, office_id: driver.officeId };
    }

    // إنشاء رمز جلسة
    const token = crypto.randomBytes(32).toString('hex');
    driverTokens.set(token, phone);

    res.json({ token, driver });
  } catch (err) {
    console.error(err); res.status(500).json({ error: 'خطأ في الخادم' });
  }
});

// الحصول على طلبيات السائق
app.get('/api/driver/orders', async (req, res) => {
  try {
    const token = req.headers['x-driver-token'];
    if (!token || !driverTokens.has(token)) {
      return res.status(401).json({ error: 'رمز جلسة غير صالح' });
    }
    const driverPhone = driverTokens.get(token);

    let driver = null;
    if (useDb) {
      const r = await pool.query('SELECT id, name, phone, office_id FROM drivers WHERE phone = $1', [driverPhone]);
      if (r.rows.length === 0) return res.status(401).json({ error: 'سائق غير موجود' });
      driver = r.rows[0];
    } else {
      driver = memoryDrivers.find(d => d.phone === driverPhone);
      if (!driver) return res.status(401).json({ error: 'سائق غير موجود' });
    }

    const driverName = driver.name || driver.name;

    if (useDb) {
      const r = await pool.query(
        `SELECT * FROM orders WHERE driver_name = $1 AND delivery_status NOT IN ('DELIVERED','CANCELLED','RETURNED') ORDER BY 
         CASE delivery_status 
           WHEN 'OUT_FOR_DELIVERY' THEN 0
           WHEN 'PREPARING' THEN 1
           WHEN 'PENDING' THEN 2
         END ASC, created_at DESC`,
        [driverName]
      );
      return res.json(r.rows.map(formatOrder));
    }

    let orders = memoryOrders.filter(o =>
      o.driverName === driverName &&
      !['DELIVERED', 'CANCELLED', 'RETURNED'].includes(o.deliveryStatus || 'PENDING')
    );
    orders.sort((a, b) => {
      const order = { OUT_FOR_DELIVERY: 0, PREPARING: 1, PENDING: 2 };
      const sa = order[a.deliveryStatus || 'PENDING'] ?? 2;
      const sb = order[b.deliveryStatus || 'PENDING'] ?? 2;
      if (sa !== sb) return sa - sb;
      return new Date(b.createdAt) - new Date(a.createdAt);
    });
    res.json(orders);
  } catch (err) {
    console.error(err); res.status(500).json({ error: 'خطأ في الخادم' });
  }
});

// تحديث حالة طلبية من السائق
app.put('/api/driver/orders/:id/status', async (req, res) => {
  try {
    const token = req.headers['x-driver-token'];
    if (!token || !driverTokens.has(token)) {
      return res.status(401).json({ error: 'رمز جلسة غير صالح' });
    }
    const { status } = req.body;
    const validStatuses = ['PREPARING', 'OUT_FOR_DELIVERY', 'DELIVERED', 'RETURNED'];
    if (!validStatuses.includes(status)) {
      return res.status(400).json({ error: 'حالة غير صالحة', validStatuses });
    }
    const orderId = parseInt(req.params.id);

    if (useDb) {
      const r = await pool.query(
        'UPDATE orders SET delivery_status = $1, updated_at = NOW() WHERE id = $2 AND delivery_status NOT IN ($3,$4) RETURNING *',
        [status, orderId, 'DELIVERED', 'CANCELLED']
      );
      if (r.rows.length === 0) return res.status(404).json({ error: 'الطلبية غير موجودة أو مكتملة' });
      const order = formatOrder(r.rows[0]);
      notifyClients(order.officeId || 0, { type: 'status_update', order });
      notifyDriver(order.driverPhone, { type: 'status_update', order });
      return res.json({ order });
    }

    const idx = memoryOrders.findIndex(o => o.id === orderId && !['DELIVERED', 'CANCELLED'].includes(o.deliveryStatus || 'PENDING'));
    if (idx === -1) return res.status(404).json({ error: 'الطلبية غير موجودة أو مكتملة' });
    memoryOrders[idx].deliveryStatus = status;
    memoryOrders[idx].updatedAt = new Date().toISOString();
    const order = memoryOrders[idx];
    notifyClients(order.officeId || 0, { type: 'status_update', order });
    notifyDriver(order.driverPhone, { type: 'status_update', order });
    res.json({ order });
  } catch (err) {
    console.error(err); res.status(500).json({ error: 'خطأ في الخادم' });
  }
});

// تحديث معلومات السائق (من تطبيق المكتب)
app.put('/api/drivers/:id', async (req, res) => {
  try {
    const { name, phone, pin } = req.body;
    const driverId = parseInt(req.params.id);

    if (useDb) {
      let query = 'UPDATE drivers SET';
      const params = [];
      const updates = [];
      if (name) { updates.push('name = $' + (params.length + 1)); params.push(name); }
      if (phone) { updates.push('phone = $' + (params.length + 1)); params.push(phone); }
      if (pin) { updates.push('pin_hash = $' + (params.length + 1)); params.push(crypto.createHash('sha256').update(pin).digest('hex')); }
      if (updates.length === 0) return res.status(400).json({ error: 'لا توجد بيانات للتحديث' });
      query += ' ' + updates.join(', ') + ' WHERE id = $' + (params.length + 1) + ' RETURNING id, name, phone, office_id';
      params.push(driverId);
      const r = await pool.query(query, params);
      if (r.rows.length === 0) return res.status(404).json({ error: 'سائق غير موجود' });
      return res.json({ driver: r.rows[0] });
    }

    const driver = memoryDrivers.find(d => d.id === driverId);
    if (!driver) return res.status(404).json({ error: 'سائق غير موجود' });
    if (name) driver.name = name;
    if (phone) driver.phone = phone;
    if (pin) driver.pinHash = crypto.createHash('sha256').update(pin).digest('hex');
    res.json({ driver: { id: driver.id, name: driver.name, phone: driver.phone, office_id: driver.officeId } });
  } catch (err) {
    console.error(err); res.status(500).json({ error: 'خطأ في الخادم' });
  }
});

// الحصول على قائمة السائقين (لمكتب)
app.get('/api/drivers', async (req, res) => {
  try {
    const officeId = parseInt(req.query.officeId) || 0;
    if (useDb) {
      const query = officeId > 0
        ? 'SELECT id, name, phone, office_id FROM drivers WHERE office_id = $1 ORDER BY name'
        : 'SELECT id, name, phone, office_id FROM drivers ORDER BY name';
      const params = officeId > 0 ? [officeId] : [];
      const r = await pool.query(query, params);
      return res.json(r.rows);
    }
    let drivers = memoryDrivers;
    if (officeId > 0) drivers = drivers.filter(d => d.officeId === officeId);
    res.json(drivers.map(d => ({ id: d.id, name: d.name, phone: d.phone, office_id: d.officeId })));
  } catch (err) {
    console.error(err); res.status(500).json({ error: 'خطأ في الخادم' });
  }
});

// تعيين سائق لطلبية (من تطبيق المكتب)
app.put('/api/orders/:id/driver', async (req, res) => {
  try {
    const orderId = parseInt(req.params.id);
    const { driverName, driverPhone } = req.body;

    if (useDb) {
      const r = await pool.query(
        'UPDATE orders SET driver_name = $1, driver_phone = $2, updated_at = NOW() WHERE id = $3 RETURNING *',
        [driverName || '', driverPhone || '', orderId]
      );
      if (r.rows.length === 0) return res.status(404).json({ error: 'طلبية غير موجودة' });
      const order = formatOrder(r.rows[0]);
      // إشعار السائق
      if (driverPhone) notifyDriver(driverPhone, { type: 'new_assignment', order });
      notifyClients(order.officeId || 0, { type: 'status_update', order });
      return res.json({ order });
    }

    const idx = memoryOrders.findIndex(o => o.id === orderId);
    if (idx === -1) return res.status(404).json({ error: 'طلبية غير موجودة' });
    memoryOrders[idx].driverName = driverName || '';
    memoryOrders[idx].driverPhone = driverPhone || '';
    memoryOrders[idx].updatedAt = new Date().toISOString();
    const order = memoryOrders[idx];
    if (driverPhone) notifyDriver(driverPhone, { type: 'new_assignment', order });
    notifyClients(order.officeId || 0, { type: 'status_update', order });
    res.json({ order });
  } catch (err) {
    console.error(err); res.status(500).json({ error: 'خطأ في الخادم' });
  }
});

// تحديث حالة طلبية من المكتب (مع إشعار للسائق)
app.put('/api/orders/:id/status', async (req, res) => {
  try {
    const orderId = parseInt(req.params.id);
    const { status } = req.body;
    const validStatuses = ['PENDING', 'PREPARING', 'OUT_FOR_DELIVERY', 'DELIVERED', 'RETURNED', 'CANCELLED'];
    if (!validStatuses.includes(status)) {
      return res.status(400).json({ error: 'حالة غير صالحة' });
    }

    if (useDb) {
      const r = await pool.query('UPDATE orders SET delivery_status = $1, updated_at = NOW() WHERE id = $2 RETURNING *', [status, orderId]);
      if (r.rows.length === 0) return res.status(404).json({ error: 'طلبية غير موجودة' });
      const order = formatOrder(r.rows[0]);
      notifyClients(order.officeId || 0, { type: 'status_update', order });
      if (order.driverPhone) notifyDriver(order.driverPhone, { type: 'status_update', order });
      return res.json({ order });
    }

    const idx = memoryOrders.findIndex(o => o.id === orderId);
    if (idx === -1) return res.status(404).json({ error: 'طلبية غير موجودة' });
    memoryOrders[idx].deliveryStatus = status;
    memoryOrders[idx].updatedAt = new Date().toISOString();
    const order = memoryOrders[idx];
    notifyClients(order.officeId || 0, { type: 'status_update', order });
    if (order.driverPhone) notifyDriver(order.driverPhone, { type: 'status_update', order });
    res.json({ order });
  } catch (err) {
    console.error(err); res.status(500).json({ error: 'خطأ في الخادم' });
  }
});

// حذف سائق
app.delete('/api/drivers/:id', async (req, res) => {
  try {
    const driverId = parseInt(req.params.id);
    if (useDb) {
      const r = await pool.query('DELETE FROM drivers WHERE id = $1 RETURNING id', [driverId]);
      if (r.rows.length === 0) return res.status(404).json({ error: 'سائق غير موجود' });
      return res.json({ message: 'تم الحذف' });
    }
    const idx = memoryDrivers.findIndex(d => d.id === driverId);
    if (idx === -1) return res.status(404).json({ error: 'سائق غير موجود' });
    memoryDrivers.splice(idx, 1);
    res.json({ message: 'تم الحذف' });
  } catch (err) {
    console.error(err); res.status(500).json({ error: 'خطأ في الخادم' });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 Server running on port ${PORT} (${useDb ? 'PostgreSQL' : 'In-Memory'})`);
});
