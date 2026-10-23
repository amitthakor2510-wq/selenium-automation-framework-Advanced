package com.automation.core.tia;

/** How a file differs between the two git refs (or ref vs. working tree) TIA was pointed at. */
public enum ChangeType {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED
}
