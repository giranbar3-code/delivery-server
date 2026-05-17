const express = require('express');
const cors = require('cors');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;
const DATABASE_URL = process.env.DATABASE_URL;

app.use(cors());
app.use(express.json());

let memoryOrders = [];
let memoryId = 1;
let useDb = false;
let pool = null;

if (DATABASE_URL) {
  try {
    const { Pool } = require('pg');
    pool = new Pool({ connectionString: DATABASE_URL, ssl: { rejectUnauthorized: false }, connectionTimeoutMillis: 5000 });
    pool.query('SELECT 1').then(() => {
      useDb = true;
      console.log('✅ PostgreSQL متصل');
      pool.query(`CREATE TABLE IF NOT EXISTS orders (
        id SERIAL PRIMARY KEY, customer_name TEXT NOT NULL, customer_phone TEXT NOT NULL,
        order_type TEXT NOT NULL, quantity INTEGER NOT NULL DEFAULT 1, delivery_address TEXT NOT NULL,
        location_url TEXT DEFAULT '', notes TEXT DEFAULT '', items TEXT DEFAULT '[]',
        synced BOOLEAN DEFAULT false, created_at TIMESTAMPTZ DEFAULT NOW()
      )`).then(() => {
        // إضافة عمود items إذا كان موجود مسبقاً (للنسخ القديمة)
        pool.query(`ALTER TABLE orders ADD COLUMN IF NOT EXISTS items TEXT DEFAULT '[]'`).catch(() => {});
      }).catch(e => console.error('Create table error:', e.message));
    }).catch(e => {
      console.log('⚠️ PostgreSQL غير متاح، استخدام التخزين المؤقت:', e.message);
    });
  } catch (e) {
    console.log('⚠️ تعذر تحميل pg، استخدام التخزين المؤقت:', e.message);
  }
}

app.use(express.static(path.join(__dirname, 'public')));

app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.get('/api/status', (req, res) => {
  res.json({ status: 'ok', db: useDb ? 'postgresql' : 'memory', orders: memoryOrders.length });
});

app.post('/api/orders', async (req, res) => {
  try {
    const { customerName, customerPhone, orderType, quantity, deliveryAddress, locationUrl, notes, items } = req.body;
    if (!customerName || !customerPhone || !deliveryAddress) {
      return res.status(400).json({ error: 'اسم الزبون، رقم الهاتف والعنوان إجبارية' });
    }

    const itemsJson = JSON.stringify(items || []);
    const finalOrderType = orderType || (items && items.length ? items.map(i => i.name).join('، ') : 'طلب');
    const finalQuantity = quantity || (items ? items.reduce((s, i) => s + (parseInt(i.quantity) || 1), 0) : 1);

    if (useDb) {
      const r = await pool.query(
        `INSERT INTO orders (customer_name,customer_phone,order_type,quantity,delivery_address,location_url,notes,items)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8) RETURNING *`,
        [customerName, customerPhone, finalOrderType, finalQuantity, deliveryAddress, locationUrl||'', notes||'', itemsJson]
      );
      return res.status(201).json({ message: 'تم الاستلام', order: formatOrder(r.rows[0]) });
    }

    const order = {
      id: memoryId++, customerName, customerPhone, orderType: finalOrderType, quantity: finalQuantity,
      deliveryAddress, locationUrl: locationUrl||'', notes: notes||'', items: items || [],
      synced: false, createdAt: new Date().toISOString()
    };
    memoryOrders.unshift(order);
    res.status(201).json({ message: 'تم الاستلام', order });
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: 'خطأ في الخادم' });
  }
});

app.get('/api/orders', async (req, res) => {
  try {
    if (useDb) {
      const since = req.query.since || '1970-01-01';
      const r = await pool.query('SELECT * FROM orders WHERE created_at > $1 ORDER BY created_at DESC', [since]);
      return res.json(r.rows.map(formatOrder));
    }
    const since = req.query.since ? new Date(req.query.since).getTime() : 0;
    res.json(memoryOrders.filter(o => new Date(o.createdAt).getTime() > since));
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
    synced: row.synced, createdAt: row.created_at
  };
}

app.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 Server running on port ${PORT} (${useDb ? 'PostgreSQL' : 'In-Memory'})`);
});
