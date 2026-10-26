package com.gridlens.android;

/** A stale service instance cannot clear or publish over a newer explicitly requested job. */
final class UpdateDownloadOwnership {
    interface Action { void run() throws Exception; }
    private String id;
    synchronized void activate(String next) { id = next; }
    synchronized boolean isCurrent(String expected) { return expected != null && expected.equals(id); }
    synchronized boolean vacant() { return id == null; }
    synchronized boolean claim(String expected) { if (id != null && !id.equals(expected)) return false; id = expected; return true; }
    synchronized boolean release(String expected) { if (!isCurrent(expected)) return false; id = null; return true; }
    synchronized boolean runIfCurrent(String expected, Action action) throws Exception {
        if (!isCurrent(expected)) return false;
        action.run(); return true;
    }
    synchronized boolean release(String expected, Action action) throws Exception {
        if (!isCurrent(expected)) return false;
        try { action.run(); } finally { id = null; }
        return true;
    }
    synchronized boolean retireIfCurrentOrVacant(String expected, Action action) throws Exception {
        if (id != null && !isCurrent(expected)) return false;
        try { action.run(); } finally { id = null; }
        return true;
    }
}
