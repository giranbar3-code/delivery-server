const express = require('express');
const cors = require('cors');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;
const DATABASE_URL = process.env.DATABASE_URL;

app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// صفحة الترحيب الرئيسية
app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

if (!DATABASE_URL) {
  console.error('❌ DATABASE_URL غير مضبوط! أضف متغير البيئة في Render.');
} else {
  const { Pool } = require('pg');
  const pool = new Pool({ connectionString: DATABASE_URL, ssl: { rejectUnauthorized: false } });

  pool.query(`
    CREATE TABLE IF NOT EXISTS orders (
      id SERIAL PRIMARY KEY,
      customer_name TEXT NOT NULL,
      customer_phone TEXT NOT NULL,
      order_type TEXT NOT NULL,
      quantity INTEGER NOT NULL DEFAULT 1,
      delivery_address TEXT NOT NULL,
      location_url TEXT DEFAULT '',
      notes TEXT DEFAULT '',
      synced BOOLEAN DEFAULT false,
      created_at TIMESTAMPTZ DEFAULT NOW()
    )
  `).then(() => console.log('✅ جدول الطلبات جاهز'))
    .catch(err => console.error('❌ خطأ في قاعدة البيانات:', err.message));

  // ✅ العميل يقدّم طلباً جديداً
  app.post('/api/orders', async (req, res) => {
    try {
      const { customerName, customerPhone, orderType, quantity, deliveryAddress, locationUrl, notes } = req.body;
      if (!customerName || !customerPhone || !orderType || !quantity || !deliveryAddress) {
        return res.status(400).json({ error: 'جميع الحقول مطلوبة' });
      }
      const result = await pool.query(
        `INSERT INTO orders (customer_name, customer_phone, order_type, quantity, delivery_address, location_url, notes)
         VALUES ($1,$2,$3,$4,$5,$6,$7) RETURNING *`,
        [customerName, customerPhone, orderType, parseInt(quantity) || 1, deliveryAddress, locationUrl || '', notes || '']
      );
      res.status(201).json({ message: 'تم استلام الطلب', order: mapOrder(result.rows[0]) });
    } catch (err) {
      console.error(err);
      res.status(500).json({ error: 'خطأ في الخادم' });
    }
  });

  // ✅ التطبيق يسحب الطلبات الجديدة
  app.get('/api/orders', async (req, res) => {
    try {
      const since = req.query.since || '1970-01-01';
      const result = await pool.query('SELECT * FROM orders WHERE created_at > $1 ORDER BY created_at DESC', [since]);
      res.json(result.rows.map(mapOrder));
    } catch (err) {
      console.error(err);
      res.status(500).json({ error: 'خطأ في جلب الطلبات' });
    }
  });

  // ✅ التطبيق يبلغ أن الطلبات تمت مزامنتها
  app.post('/api/orders/sync', async (req, res) => {
    try {
      const { ids } = req.body;
      if (!ids || !Array.isArray(ids)) return res.status(400).json({ error: 'ids مطلوب' });
      const result = await pool.query('UPDATE orders SET synced = true WHERE id = ANY($1::int[])', [ids]);
      res.json({ message: 'تم التحديث', count: result.rowCount });
    } catch (err) {
      console.error(err);
      res.status(500).json({ error: 'خطأ في المزامنة' });
    }
  });
}

function mapOrder(row) {
  return {
    id: row.id,
    customerName: row.customer_name,
    customerPhone: row.customer_phone,
    orderType: row.order_type,
    quantity: row.quantity,
    deliveryAddress: row.delivery_address,
    locationUrl: row.location_url || '',
    notes: row.notes || '',
    synced: row.synced,
    createdAt: row.created_at
  };
}

app.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 الخادم يعمل على المنفذ ${PORT}`);
});
