package com.singleagent.Tools.FileTools.model;

import lombok.Data;

@Data
public class FileEntity {

    private String fileName;
    private String fileAbsolutePath;
    private boolean isDirectory;
    private long size;
}
