// UserMySqlIntegrationTest.java
package org.credit.biz;

import org.credit.biz.model.SysUser;
import org.credit.biz.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class UserMySqlIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldSaveAndRetrieveUserWithMysql() {
        SysUser user = SysUser.builder()
                .username("mysql_user")
                .email("mysql2@example.com")
                .nickName("MySQL Test")
                .gender("M")
                .phone("13800138000")
                .enabled(true)
                .isAdmin(false)
                .build();

        SysUser saved = userRepository.save(user);
        SysUser found = userRepository.findById(saved.getUserId()).orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.getUsername()).isEqualTo("mysql_user");
        assertThat(found.getEmail()).isEqualTo("mysql@example.com");
        assertThat(found.getEnabled()).isTrue();
        assertThat(found.getIsAdmin()).isFalse();
    }
}