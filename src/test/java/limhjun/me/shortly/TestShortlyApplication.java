package limhjun.me.shortly;

import org.springframework.boot.SpringApplication;

public class TestShortlyApplication {

	public static void main(String[] args) {
		SpringApplication.from(ShortlyApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
