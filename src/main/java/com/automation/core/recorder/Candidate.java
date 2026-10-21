package com.automation.core.recorder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One way of locating an element, as produced by the in-page recorder: a locator type plus its value. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Candidate {

    /** An ObjectRepository locator type: id, name, css, xpath or linktext. */
    public String type;
    public String value;
    /** False for position-based locators that break as soon as the page layout changes. */
    public boolean stable = true;

    public Candidate() {
    }

    public Candidate(String type, String value, boolean stable) {
        this.type = type;
        this.value = value;
        this.stable = stable;
    }

    /** The {@code type:value} form stored in the object repository. */
    public String toLocatorString() {
        return type + ":" + value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Candidate that && type.equals(that.type) && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return toLocatorString().hashCode();
    }

    @Override
    public String toString() {
        return toLocatorString();
    }
}
