package com.singleagent.Util;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class MemoryHelperUtil {

    public static Map<String,String> normalizeUserTask = new ConcurrentHashMap<>();
}
