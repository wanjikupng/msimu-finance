// Landing-page calculator. Pure illustration: sample seed values, no backend
// calls, and the page labels the figures as illustrative.
import { money, fmtDate } from "./ui.js";
import { renderPhone } from "./phone.js";

// Illustrative sample values taken from the backend seed data.
const PHONES = [
  { name: "Samsung Galaxy A15", price: 18000, deposit: 4000, daily: 70 },
  { name: "Tecno Camon 20", price: 22000, deposit: 5000, daily: 90 },
  { name: "Infinix Hot 40", price: 16000, deposit: 3500, daily: 60 },
];

const $ = (id) => document.getElementById(id);

function currentInputs() {
  const phone = PHONES[Number($("calc-phone").value)] || PHONES[0];
  const deposit = Math.max(0, Number($("calc-deposit").value) || 0);
  const daily = Math.max(1, Number($("calc-daily").value) || 1);
  return { phone, deposit: Math.min(deposit, phone.price), daily };
}

function daysToOwn(phone, deposit, daily) {
  return Math.max(0, Math.ceil((phone.price - deposit) / daily));
}

function render() {
  const { phone, deposit, daily } = currentInputs();
  const daySlider = $("calc-day");
  const totalDays = daysToOwn(phone, deposit, daily);

  // Keep the day scrubber in range of the current scenario.
  daySlider.max = String(Math.max(totalDays, 1));
  if (Number(daySlider.value) > totalDays) daySlider.value = String(totalDays);

  const day = Number(daySlider.value) || 0;
  const paid = Math.min(phone.price, deposit + daily * day);
  const pct = Math.round((paid / phone.price) * 100);

  $("calc-daily-val").textContent = String(daily);
  $("calc-day-val").textContent = String(day);
  $("calc-pct").textContent = `${pct}% paid`;

  const finish = new Date();
  finish.setDate(finish.getDate() + totalDays);
  $("calc-lines").innerHTML =
    `Price ${money(phone.price)} · deposit ${money(deposit)}<br>` +
    `Financed ${money(phone.price - deposit)} at ${money(daily)}/day<br>` +
    `Own it in about ${totalDays} days (${fmtDate(finish)}).`;

  renderPhone($("calc-phone-stage"), {
    pct,
    state: pct >= 100 ? "owned" : "active",
    label: `Illustration: phone ${pct} percent paid`,
  });
}

function init() {
  const phoneSelect = $("calc-phone");
  PHONES.forEach((p, i) => {
    const opt = document.createElement("option");
    opt.value = String(i);
    opt.textContent = `${p.name} — ${money(p.price)}`;
    phoneSelect.appendChild(opt);
  });

  phoneSelect.addEventListener("change", () => {
    const phone = PHONES[Number(phoneSelect.value)] || PHONES[0];
    $("calc-deposit").value = String(phone.deposit);
    $("calc-daily").value = String(phone.daily);
    $("calc-day").value = "0";
    render();
  });
  for (const id of ["calc-deposit", "calc-daily", "calc-day"]) {
    $(id).addEventListener("input", render);
  }
  render();
}

init();
