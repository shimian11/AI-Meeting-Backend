
package com.banma.interview;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 讯智后管应用
 */
@SpringBootApplication
@MapperScan("com.banma.interview.**.dao.mapper")
@EnableScheduling
public class BanmaAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(BanmaAdminApplication.class, args);
    }
}
