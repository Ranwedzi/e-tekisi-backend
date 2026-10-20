package com.etekisi.etekisi.model;

/**
 * Vetting state of a driver account. Only APPROVED drivers can go online,
 * make fare offers or join rank queues. Stored as strings; never rename.
 *
 * <pre>
 *   PENDING_APPROVAL --operator approves--> APPROVED
 *          |                                   |
 *          +--operator rejects--> REJECTED <---+  (operators can approve again later)
 * </pre>
 */
public enum DriverApproval {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
}
