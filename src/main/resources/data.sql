DELETE FROM unlock_codes;
DELETE FROM device_lock_keys;
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

-- A plan fixes the repayment term. The daily installment is computed per
-- contract as financed amount / duration_days (rounded up to the shilling),
-- so a plan always finishes when its name says. deposit_amount and
-- daily_amount are indicative figures for a KES 14,000 balance (Galaxy A15
-- with the minimum deposit); they never drive contract terms.
INSERT INTO payment_plans (plan_name, duration_days, deposit_amount, daily_amount) VALUES
('Flex 200 Days', 200, 4000.00, 70.00),
('Standard 120 Days', 120, 4000.00, 117.00),
('Fast 90 Days', 90, 4000.00, 156.00);

INSERT INTO payment_modes (mode_name, description) VALUES
('M-PESA', 'Mobile money payment'),
('Airtel Money', 'Airtel wallet transfer'),
('Cash', 'Cash payment in-store');

INSERT INTO products (name, description, full_price, down_payment, daily_amount, payment_plan_name, payment_mode) VALUES
('Samsung Galaxy A15', '6.5" display • 128GB storage', 18000.00, 4000.00, 70.00, 'Flex 200 Days', 'M-PESA'),
('Tecno Camon 20', 'AI camera • 256GB memory', 22000.00, 5000.00, 90.00, 'Standard 120 Days', 'Airtel Money'),
('Infinix Hot 40', 'Large battery • dual camera', 16000.00, 3500.00, 60.00, 'Flex 200 Days', 'Cash'),
('Redmi 14C', '6.88" display • 128GB storage', 15000.00, 3000.00, 55.00, 'Flex 200 Days', 'M-PESA'),
('Tecno Spark 10', '50MP camera • 128GB memory', 14000.00, 3000.00, 50.00, 'Standard 120 Days', 'Cash');
