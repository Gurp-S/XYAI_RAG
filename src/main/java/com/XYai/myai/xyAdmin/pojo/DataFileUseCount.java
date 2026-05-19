package com.XYai.myai.xyAdmin.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DataFileUseCount {

    private LocalDate localDate;
    private Long userCount;
}
