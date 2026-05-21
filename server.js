const express = require('express');
const cors = require('cors');
const path = require('path');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const dns = require('dns');

const app = express();
const PORT = process.env.PORT || 3000;
const DATABASE_URL = process.env.DATABASE_URL;
const API_KEY = process.env.API_KEY;
const MAX_MEMORY_ORDERS = 500;
const MAX_ORDERS_PER_PHONE_PER_HOUR = 5;

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

app.use(express.json({ limit: '1mb' }));

// التحقق من مفتاح API
app.use((req, res, next) => {
  if (req.path.startsWith('/api/') && req.path !== '/api/status' && !(req.method === 'POST' && req.path === '/api/orders')) {
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
      office_id BIGINT NOT NULL DEFAULT 0
    )`);
    // تأكد من وجود عمود office_id (للهجرة من الإصدارات القديمة)
    await p.query(`ALTER TABLE orders ADD COLUMN IF NOT EXISTS office_id BIGINT NOT NULL DEFAULT 0`)
      .catch(e => {});
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

app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.get('/office/:id', (req, res) => {
  const officeId = parseInt(req.params.id) || 0;
  const fs = require('fs');
  let html = fs.readFileSync(path.join(__dirname, 'public', 'index.html'), 'utf8');
  html = html.replace(/\{\{OFFICE_ID\}\}/g, officeId);
  res.send(html);
});

app.get('/api/status', (req, res) => {
  res.json({ status: 'ok', db: useDb ? 'postgresql' : 'memory', orders: memoryOrders.length });
});

app.post('/api/orders', async (req, res) => {
  try {
    const { customerName, customerPhone, orderType, quantity, deliveryAddress, locationUrl, notes, items, honeypot, pageLoad, officeId } = req.body;

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
      return res.status(201).json({ message: 'تم الاستلام', order: formatOrder(r.rows[0]) });
    }

    const order = {
      id: memoryId++, customerName, customerPhone, orderType: finalOrderType, quantity: finalQuantity,
      deliveryAddress, locationUrl: locationUrl||'', notes: notes||'', items: items || [],
      verified: isVerified, clientIp, officeId: finalOfficeId,
      synced: false, createdAt: new Date().toISOString()
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
        ? 'SELECT * FROM orders WHERE created_at > $1 AND office_id = $2 ORDER BY created_at DESC'
        : 'SELECT * FROM orders WHERE created_at > $1 ORDER BY created_at DESC';
      const params = officeId > 0 ? [since, officeId] : [since];
      const r = await pool.query(query, params);
      return res.json(r.rows.map(formatOrder));
    }
    const sinceTime = new Date(since).getTime() || 0;
    let filtered = memoryOrders.filter(o => new Date(o.createdAt).getTime() > sinceTime);
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
    officeId: row.office_id || 0
  };
}

app.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 Server running on port ${PORT} (${useDb ? 'PostgreSQL' : 'In-Memory'})`);
});
