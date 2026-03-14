package org.credit.biz.model;
import java.util.Map;

/** TODO2：充血模型改造 */
public class User {

    private String email;
    private String password;

    public User() {
    }

    public User(String email, String password) {
        this.email = email;
        this.password = password;
    }

    public  void saveToDb(Map<String, User>database){
        database.put(this.email, this);
    }

    public boolean verifyPassword(String inputpassword) {
        return this.password.equals(inputpassword);
    }

}