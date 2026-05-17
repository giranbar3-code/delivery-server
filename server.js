const express = require('express');
const cors = require('cors');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;
const DATABASE_URL = process.env.DATABASE_URL;

app.use(cors());
app.use(express.json());

// صفحة استقبال الطلبات
app.use(express.static(path.join(__dirname, 'public')));

app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.get('/api/status', (req, res) => {
  res.json({ status: 'ok', db: DATABASE_URL ? 'موجود' : 'غير موجود' });
});

if (DATABASE_URL) {
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
  `).catch(err => console.error('DB error:', err.message));

  app.post('/api/orders', async (req, res) => {
    try {
      const { customerName, customerPhone, orderType, quantity, deliveryAddress, locationUrl, notes } = req.body;
      if (!customerName || !customerPhone || !orderType || !quantity || !deliveryAddress) {
        return res.status(400).json({ error: 'جميع الحقول مطلوبة' });
      }
      const r = await pool.query(
        `INSERT INTO orders (customer_name,customer_phone,order_type,quantity,delivery_address,location_url,notes)
         VALUES ($1,$2,$3,$4,$5,$6,$7) RETURNING *`,
        [customerName, customerPhone, orderType, parseInt(quantity)||1, deliveryAddress, locationUrl||'', notes||'']
      );
      res.status(201).json({ message: 'تم الاستلام', order: {
        id: r.rows[0].id, customerName: r.rows[0].customer_name, customerPhone: r.rows[0].customer_phone,
        orderType: r.rows[0].order_type, quantity: r.rows[0].quantity, deliveryAddress: r.rows[0].delivery_address,
        locationUrl: r.rows[0].location_url||'', notes: r.rows[0].notes||'', synced: r.rows[0].synced,
        createdAt: r.rows[0].created_at
      }});
    } catch (err) { console.error(err); res.status(500).json({ error: 'خطأ في الخادم' }); }
  });

  app.get('/api/orders', async (req, res) => {
    try {
      const since = req.query.since || '1970-01-01';
      const r = await pool.query('SELECT * FROM orders WHERE created_at > $1 ORDER BY created_at DESC', [since]);
      res.json(r.rows.map(row => ({
        id: row.id, customerName: row.customer_name, customerPhone: row.customer_phone,
        orderType: row.order_type, quantity: row.quantity, deliveryAddress: row.delivery_address,
        locationUrl: row.location_url||'', notes: row.notes||'', synced: row.synced, createdAt: row.created_at
      })));
    } catch (err) { console.error(err); res.status(500).json({ error: 'خطأ' }); }
  });

  app.post('/api/orders/sync', async (req, res) => {
    try {
      const { ids } = req.body;
      if (!ids||!Array.isArray(ids)) return res.status(400).json({ error: 'ids مطلوب' });
      const r = await pool.query('UPDATE orders SET synced=true WHERE id=ANY($1::int[])', [ids]);
      res.json({ message: 'تم', count: r.rowCount });
    } catch (err) { console.error(err); res.status(500).json({ error: 'خطأ' }); }
  });
}

app.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 Server running on port ${PORT}`);
});
