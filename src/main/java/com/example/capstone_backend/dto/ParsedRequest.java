package com.example.capstone_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter
@NoArgsConstructor  // 인자 없는 생성자
@AllArgsConstructor // 모든 인자가 있는 생성자
public class ParsedRequest {
    private Long budget;
    private String usage;
}