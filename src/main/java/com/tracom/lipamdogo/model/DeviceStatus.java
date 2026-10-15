package com.tracom.lipamdogo.model;

/**
 * Physical-asset lifecycle for a {@link Device}.
 *
 * <ul>
 *   <li>AVAILABLE — in stock, ready to be assigned.</li>
 *   <li>RESERVED — held for a specific upcoming assignment, not yet handed over.</li>
 *   <li>ASSIGNED — handed to a merchant/agent channel, not yet with a customer.</li>
 *   <li>FINANCED — with a customer under an active financing relationship.</li>
 *   <li>OVERDUE — with a customer whose financing has fallen behind. The device
 *       is still FINANCED in location terms; this flag mirrors the loan book.</li>
 *   <li>PAID_OFF — customer completed financing; device is customer-owned.
 *       Terminal state in Phase 2 (no transition back without a future
 *       return/release operation).</li>
 *   <li>REPAIR — with the repair channel (Phase 3 repair centre will own this).</li>
 *   <li>LOST — reported lost; only RETIRED may follow.</li>
 *   <li>RETIRED — permanently out of circulation. Terminal state.</li>
 * </ul>
 */
public enum DeviceStatus {
    AVAILABLE,
    RESERVED,
    ASSIGNED,
    FINANCED,
    OVERDUE,
    PAID_OFF,
    REPAIR,
    LOST,
    RETIRED
}
