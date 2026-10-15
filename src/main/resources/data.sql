DELETE FROM reconciliation_records;
DELETE FROM reconciliation_batches;
DELETE FROM provider_transactions;
DELETE FROM payment_receipts;
DELETE FROM payment_allocations;
DELETE FROM payment_transactions;
DELETE FROM loan_contracts;
DELETE FROM financing_applications;
DELETE FROM cash_loans;
DELETE FROM devices;
DELETE FROM agents;
DELETE FROM merchants;
DELETE FROM payment_plans;
DELETE FROM payment_modes;
DELETE FROM products;

-- Identity rows (admin/demo/agent/merchant/customer accounts) are created by
-- IdentityBootstrap, and merchants/agents/devices by DomainBootstrap, never
-- here, so passwords are always BCrypt-hashed by application code.

INSERT INTO payment_plans (plan_name, duration_days, deposit_amount, daily_amount) VALUES
('Classic 90 Days', 90, 4000.00, 70.00),
('Smart 120 Days', 120, 5000.00, 60.00),
('Premium 180 Days', 180, 6000.00, 55.00);

INSERT INTO payment_modes (mode_name, description) VALUES
('M-PESA', 'Mobile money payment'),
('Airtel Money', 'Airtel wallet transfer'),
('Cash', 'Cash payment in-store');

INSERT INTO products (name, description, full_price, down_payment, daily_amount, payment_plan_name, payment_mode) VALUES
('Samsung Galaxy A15', '6.5" display • 128GB storage', 18000.00, 4000.00, 70.00, 'Classic 90 Days', 'M-PESA'),
('Tecno Camon 20', 'AI camera • 256GB memory', 22000.00, 5000.00, 90.00, 'Smart 120 Days', 'Airtel Money'),
('Infinix Hot 40', 'Large battery • dual camera', 16000.00, 3500.00, 60.00, 'Premium 180 Days', 'Cash'),
('Redmi 14C', '6.88" display • 128GB storage', 15000.00, 3000.00, 55.00, 'Classic 90 Days', 'M-PESA'),
('Tecno Spark 10', '50MP camera • 128GB memory', 14000.00, 3000.00, 50.00, 'Smart 120 Days', 'Cash');
