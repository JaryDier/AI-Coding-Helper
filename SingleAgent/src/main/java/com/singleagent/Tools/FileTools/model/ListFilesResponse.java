package com.singleagent.Tools.FileTools.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ListFilesResponse {

    private String rooPath;  //查询的根目录
    private int total; //文件总数
    private List<FileEntity> files = new ArrayList<>();

}
