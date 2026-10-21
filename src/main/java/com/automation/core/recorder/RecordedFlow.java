package com.automation.core.recorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The result of turning a recording into keyword steps. */
public final class RecordedFlow {

    public final List<RecordedStep> steps = new ArrayList<>();
    /** Repository keys the steps use, in first-use order. */
    public final Map<String, LocatorSpec> locators = new LinkedHashMap<>();
    /**
     * Secret values that were typed during the recording, by the environment-variable name the script
     * refers to them with. Never written to disk; only used to replay the recording straight away.
     */
    public final Map<String, String> secrets = new LinkedHashMap<>();
    /** Things the user should do or check by hand after recording. */
    public final List<String> notes = new ArrayList<>();
}
