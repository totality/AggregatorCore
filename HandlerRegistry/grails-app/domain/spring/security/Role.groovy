package spring.security

class Role {

	String authority
        
    static mapping = {
        cache true
        table "shiro_role"
        authority column: "name"
    }

	static constraints = {
		authority blank: false, unique: true
	}
}
