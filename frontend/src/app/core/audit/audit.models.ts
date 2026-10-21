export const auditFilterKeys = [
  'eventType',
  'actorType',
  'actorId',
  'entityType',
  'entityId',
  'correlationId',
  'producer',
  'eventId',
  'from',
  'to',
] as const;

export type AuditFilters = Partial<Record<(typeof auditFilterKeys)[number], string>>;

export interface AuditQuery extends AuditFilters {
  page: number;
  size: number;
}

export interface AuditRow {
  id: string;
  eventId: string;
  eventType: string;
  actorType: string | null;
  actorId: string | null;
  entityType: string;
  entityId: string;
  correlationId: string | null;
  producer: string | null;
  occurredAt: string;
  createdAt: string;
}

export interface AuditDetail extends AuditRow {
  metadata: unknown;
}

export interface AuditPage {
  content: AuditRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
