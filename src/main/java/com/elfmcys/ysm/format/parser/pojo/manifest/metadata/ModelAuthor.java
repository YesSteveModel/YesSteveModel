package com.elfmcys.ysm.format.parser.pojo.manifest.metadata;

import java.util.LinkedHashMap;

public class ModelAuthor {
    public String name = "";
    public String role = "";
    public String avatar = "";
    public LinkedHashMap<String, String> contact = new LinkedHashMap<>();
    public String comment = "";

    public ModelAuthor() {}

    public ModelAuthor(String name) {
        this.name = name;
    }
}
