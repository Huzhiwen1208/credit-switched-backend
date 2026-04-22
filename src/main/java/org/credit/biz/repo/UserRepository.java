// UserRepository.java
package org.credit.biz.repo;

import org.credit.biz.model.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<SysUser, Long> {
}