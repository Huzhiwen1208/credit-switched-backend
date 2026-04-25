package org.credit.biz.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {
    private Long id;
    private String username;
    private String email;

    public static UserProfile fromUser(User user) {
        return new UserProfile(user.getId(), user.getUsername(), user.getEmail());
    }
}
