package com.singleagent.Controller.Response;

import lombok.Data;

@Data
public class InterruptMessage {
    String id;
    String name;
    String arguments;
    String description;
}