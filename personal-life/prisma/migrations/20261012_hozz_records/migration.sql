-- Runs after 202610110001_initial, on both existing and new installations.
ALTER TABLE "SyncLog" ADD COLUMN "retainedCount" INTEGER NOT NULL DEFAULT 0;
CREATE TABLE "HealthSourceRecord" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "source" TEXT NOT NULL,
    "recordId" TEXT NOT NULL,
    "recordType" TEXT NOT NULL,
    "recordVersion" INTEGER NOT NULL,
    "deleted" BOOLEAN NOT NULL DEFAULT false,
    "payload" JSONB NOT NULL,
    "startTime" TIMESTAMPTZ(3),
    "endTime" TIMESTAMPTZ(3),
    "observedAt" TIMESTAMPTZ(3),
    "updatedAt" TIMESTAMPTZ(3) NOT NULL,
    CONSTRAINT "HealthSourceRecord_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "HealthSourceRecord_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX "HealthSourceRecord_userId_source_recordId_key" ON "HealthSourceRecord"("userId", "source", "recordId");
CREATE INDEX "HealthSourceRecord_userId_source_observedAt_idx" ON "HealthSourceRecord"("userId", "source", "observedAt");
CREATE INDEX "HealthSourceRecord_userId_source_recordType_idx" ON "HealthSourceRecord"("userId", "source", "recordType");
