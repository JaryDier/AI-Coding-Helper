package com.singleagent.Tools.FileTools.model;

import lombok.Data;

@Data
public class FileCreateResponse {

    private boolean success;

    private String targetPath;

    private String targetType;

    private boolean created;

    private boolean overwritten;

    private boolean alreadyExists;

    private boolean parentDirectoriesCreated;

    private long bytesWritten;

    private String errorMessage;

    private String summary;
}
