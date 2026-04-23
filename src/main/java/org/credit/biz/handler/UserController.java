package org.credit.biz.handler;

import org.credit.biz.model.UserA;
import org.credit.biz.service.UserAService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {

    @Autowired
    private UserAService userAService;

    @GetMapping("/{id}")
    public UserA getUser(@PathVariable Integer id) {
        System.out.println("收到请求，准备调用 Service 层...");
        return userAService.getUserById(id);
    }
}
