package org.credit.biz.Respository;

import org.credit.biz.model.UserA;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<UserA, Integer> {
    // 继承 JpaRepository 后，自动拥有 save, findById 等方法
}
