package com.unisound.iot.controller.spring.mybatis;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CityDao {

    @Select("select * from user ")
    public List<String> query();


}
